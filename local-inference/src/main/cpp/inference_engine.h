#pragma once

#include "chat.h"
#include "llama.h"
#include <chrono>
#include <functional>
#include <memory>
#include <stdexcept>
#include <string>

namespace inkpilot {

struct Limits {
    int context_tokens;
    int output_tokens;
    int reply_characters;
    int64_t deadline_millis;
    int threads;
};

struct Cancelled : std::runtime_error { Cancelled() : std::runtime_error("Inference cancelled") {} };
struct TimedOut : std::runtime_error { TimedOut() : std::runtime_error("Inference time budget exceeded") {} };

struct Budget {
    std::chrono::steady_clock::time_point deadline;
    std::function<bool()> cancelled;
    bool expired() const { return std::chrono::steady_clock::now() >= deadline; }
    void check() const {
        if (cancelled()) throw Cancelled();
        if (expired()) throw TimedOut();
    }
};

using Model = std::unique_ptr<llama_model, decltype(&llama_model_free)>;
using Context = std::unique_ptr<llama_context, decltype(&llama_free)>;
using Batch = std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)>;
using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;

Sampler make_sampler(const llama_vocab * vocabulary, const common_chat_session & chat, const Limits & limits);

std::string generate(const std::string & model_path, const std::string & instructions,
                     const std::string & user_data, const Limits & limits,
                     const std::function<bool()> & cancelled);

std::string generate_reply(llama_context * context, const llama_vocab * vocabulary,
                           common_chat_session & chat, const Limits & limits, Budget & budget);

} // namespace inkpilot
