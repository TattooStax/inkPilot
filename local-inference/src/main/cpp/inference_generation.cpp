#include "inference_engine.h"
#include <algorithm>
#include <vector>

namespace inkpilot {
namespace {
std::vector<llama_token> tokenize(const llama_vocab * vocabulary, const common_chat_prompt & prompt) {
    std::vector<llama_token> tokens;
    bool first = true;
    for (const auto & part : prompt.parts) {
        // Template control tokens are allowed only in template-owned segments.
        auto next = common_tokenize(vocabulary, part.text, first, !part.is_input);
        tokens.insert(tokens.end(), next.begin(), next.end());
        first = false;
    }
    return tokens;
}

void decode(llama_context * context, llama_batch_ext * batch, const llama_token * tokens,
            int count, int position, Budget & budget) {
    budget.check();
    llama_batch_ext_clear(batch);
    for (int index = 0; index < count; ++index) {
        const int slot = llama_batch_ext_add_token(batch, 0, tokens[index]);
        const llama_pos token_position = position + index;
        if (slot < 0 || !llama_batch_ext_set_pos(batch, slot, &token_position)) {
            throw std::runtime_error("Unable to prepare model batch");
        }
    }
    llama_batch_ext_set_output_logits(batch, count - 1, true);
    const int result = llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch);
    budget.check();
    if (result != 0) throw std::runtime_error("Local model decoding failed");
}

Sampler sampler(const llama_vocab * vocabulary) {
    Sampler result(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    llama_sampler_chain_add(result.get(), llama_sampler_init_top_k(50));
    llama_sampler_chain_add(result.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocabulary), 64, 1.1f, 0, 0));
    llama_sampler_chain_add(result.get(), llama_sampler_init_temp(0.1f));
    llama_sampler_chain_add(result.get(), llama_sampler_init_dist(42));
    return result;
}
} // namespace

std::string generate_reply(llama_context * context, const llama_vocab * vocabulary,
                           common_chat_session & chat, const Limits & limits, Budget & budget) {
    auto tokens = tokenize(vocabulary, chat.prompt());
    if (tokens.empty() || tokens.size() + limits.output_tokens > llama_n_ctx(context)) {
        throw std::runtime_error("Customer context exceeds the local token budget");
    }
    Batch batch(llama_batch_ext_init(context), llama_batch_ext_free);
    if (!batch) throw std::runtime_error("Unable to allocate model batch");
    for (size_t start = 0; start < tokens.size(); start += 64) {
        decode(context, batch.get(), tokens.data() + start, static_cast<int>(std::min<size_t>(64, tokens.size() - start)),
               static_cast<int>(start), budget);
    }
    auto sampling = sampler(vocabulary);
    common_chat_input generated;
    bool finished = false;
    for (int index = 0; index < limits.output_tokens; ++index) {
        budget.check();
        const auto token = llama_sampler_sample(sampling.get(), context, -1);
        if (llama_vocab_is_eog(vocabulary, token)) {
            finished = true;
            break;
        }
        generated.append(common_token_to_piece(vocabulary, token, true), token);
        // Bound intermediate reasoning as well as final answer bytes.
        if (generated.text.size() > 16384) throw std::runtime_error("Model output exceeded its byte budget");
        bool stop = false;
        for (const auto & marker : chat.additional_stops()) {
            if (!marker.empty() && generated.text.size() >= marker.size() &&
                generated.text.compare(generated.text.size() - marker.size(), marker.size(), marker) == 0) {
                generated.truncate(generated.text.size() - marker.size());
                stop = true;
                break;
            }
        }
        if (stop) { finished = true; break; }
        decode(context, batch.get(), &token, 1, static_cast<int>(tokens.size()) + index, budget);
    }
    if (!finished) throw std::runtime_error("Model response exceeded its token budget");
    const auto & result = chat.finish(generated);
    if (!result.tool_calls.empty() || !result.content_parts.empty() || result.content.empty() ||
        result.content.size() > static_cast<size_t>(limits.reply_characters) * 4) {
        throw std::runtime_error("Model did not return a bounded text reply");
    }
    budget.check();
    return result.content;
}

} // namespace inkpilot
