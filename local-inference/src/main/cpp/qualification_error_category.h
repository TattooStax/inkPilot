#pragma once

#include <exception>
#include <string_view>

namespace inkpilot {

// Host diagnostic only. Compare exact engine failure strings and emit only a
// closed category; exception messages can contain model or customer material.
inline const char * qualification_error_category(const std::exception & failure) noexcept {
    const char * detail = failure.what();
    if (!detail) return "OTHER";
    const std::string_view message(detail);
    if (message == "Model response exceeded its token budget") return "OUTPUT_TOKEN_BUDGET";
    if (message == "Model output exceeded its byte budget") return "OUTPUT_BYTE_BUDGET";
    if (message == "Inference time budget exceeded") return "DEADLINE";
    if (message == "Inference cancelled") return "CANCELLED";
    if (message == "Customer context exceeds the local token budget") return "CONTEXT_BUDGET";
    if (message == "Model did not return a bounded text reply") return "BOUNDED_REPLY_PARSE";
    if (message == "Unable to load local GGUF" ||
        message == "This runtime requires an LFM3 text model" ||
        message == "Model chat template is missing") return "MODEL_LOAD";
    if (message == "Unable to apply model chat template" ||
        message == "Model template is missing reasoning controls") return "TEMPLATE";
    if (message == "Unable to allocate model context" ||
        message == "Unable to allocate model batch") return "RESOURCE";
    return "OTHER";
}

} // namespace inkpilot
