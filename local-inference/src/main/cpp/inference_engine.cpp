#include "inference_engine.h"
#include "log.h"
#include <mutex>

namespace inkpilot {
namespace {
std::once_flag initialized;
std::mutex engine_mutex;

void initialize() {
    // Never send customer prompt/model output to logcat or a log file.
    common_log_set_stdo(common_log_main(), false);
    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    llama_backend_init();
}

Model load(const std::string & path, Budget & budget) {
    auto parameters = llama_model_default_params();
    parameters.n_gpu_layers = 0;
    parameters.load_mode = LLAMA_LOAD_MODE_MMAP;
    parameters.progress_callback = [](float, void * data) {
        return !static_cast<Budget *>(data)->expired();
    };
    parameters.progress_callback_user_data = &budget;
    Model model(llama_model_load_from_file(path.c_str(), parameters), llama_model_free);
    budget.check();
    if (!model) throw std::runtime_error("Unable to load local GGUF");
    char architecture[64] = {};
    llama_model_meta_val_str(model.get(), "general.architecture", architecture, sizeof(architecture));
    if (std::string(architecture) != "lfm3") throw std::runtime_error("This runtime requires an LFM3 text model");
    if (!llama_model_chat_template(model.get(), nullptr)) throw std::runtime_error("Model chat template is missing");
    return model;
}

Context make_context(llama_model * model, const Limits & limits, Budget & budget) {
    auto parameters = llama_context_default_params();
    parameters.n_ctx = limits.context_tokens;
    parameters.n_batch = 64;
    parameters.n_ubatch = 64;
    parameters.n_seq_max = 1;
    parameters.n_threads = limits.threads;
    parameters.n_threads_batch = limits.threads;
    parameters.offload_kqv = false;
    parameters.op_offload = false;
    parameters.abort_callback = [](void * data) { return static_cast<Budget *>(data)->expired(); };
    parameters.abort_callback_data = &budget;
    Context context(llama_init_from_model(model, parameters), llama_free);
    budget.check();
    if (!context) throw std::runtime_error("Unable to allocate model context");
    return context;
}
} // namespace

std::string generate(const std::string & model_path, const std::string & instructions,
                     const std::string & user_data, const Limits & limits,
                     const std::function<bool()> & cancelled) {
    if (limits.context_tokens < 512 || limits.context_tokens > 4096 || limits.output_tokens < 16 ||
        limits.output_tokens > 512 || limits.reply_characters < 1 || limits.reply_characters > 4000 ||
        limits.threads < 1 || limits.threads > 4 || limits.deadline_millis < 1000 ||
        limits.deadline_millis > 180000 || instructions.size() > 32000 || user_data.size() > 64000) {
        throw std::invalid_argument("Invalid inference budget");
    }
    std::lock_guard<std::mutex> lock(engine_mutex);
    Budget budget{std::chrono::steady_clock::now() + std::chrono::milliseconds(limits.deadline_millis), cancelled};
    budget.check();
    std::call_once(initialized, initialize);
    auto model = load(model_path, budget);
    auto context = make_context(model.get(), limits, budget);
    const auto * vocabulary = llama_model_get_vocab(model.get());
    auto templates = common_chat_templates_init(model.get(), "");
    common_chat_templates_inputs inputs;
    common_chat_msg system;
    system.role = "system";
    system.content = instructions;
    common_chat_msg user;
    user.role = "user";
    user.content = user_data;
    inputs.messages = {system, user};
    inputs.enable_thinking = false;
    inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
    inputs.add_bos = llama_vocab_get_add_bos(vocabulary);
    common_chat_session chat(templates.get(), vocabulary, inputs);
    budget.check();
    if (!chat.has_template()) throw std::runtime_error("Unable to apply model chat template");
    return generate_reply(context.get(), vocabulary, chat, limits, budget);
}

} // namespace inkpilot
