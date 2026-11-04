#include "inference_engine.h"
#include "reasoning-budget.h"
#include <algorithm>
#include <vector>

namespace inkpilot {

Sampler make_sampler(const llama_vocab * vocabulary, const common_chat_session & chat, const Limits & limits) {
    const auto start = common_tokenize(vocabulary, chat.thinking_start_tag(), false, true);
    std::vector<llama_tokens> ends;
    for (const auto & tag : chat.thinking_end_tags()) {
        auto tokens = common_tokenize(vocabulary, tag, false, true);
        if (!tokens.empty()) ends.push_back(std::move(tokens));
    }
    if (start.empty() || ends.empty()) {
        throw std::runtime_error("Model template is missing reasoning controls");
    }

    // Cap each reasoning block with the actual template tokens. The upstream
    // sampler re-arms on another block; all generated tokens still share the
    // existing total limit, and incomplete final answers remain failures.
    const int reasoning_tokens = std::min(64, limits.output_tokens / 4);
    Sampler reasoning(common_reasoning_budget_init(vocabulary, {start}, ends, ends.front(), reasoning_tokens), llama_sampler_free);
    for (const auto token : common_tokenize(vocabulary, chat.generation_prompt(), false, true)) {
        llama_sampler_accept(reasoning.get(), token);
    }

    Sampler result(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    llama_sampler_chain_add(result.get(), reasoning.release());
    llama_sampler_chain_add(result.get(), llama_sampler_init_top_k(50));
    llama_sampler_chain_add(result.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocabulary), 64, 1.1f, 0, 0));
    llama_sampler_chain_add(result.get(), llama_sampler_init_temp(0.1f));
    llama_sampler_chain_add(result.get(), llama_sampler_init_dist(42));
    return result;
}

} // namespace inkpilot
