#include "inference_engine.h"
#include "qualification_error_category.h"
#include <nlohmann/json.hpp>
#include <chrono>
#include <iostream>
#include <string>
#include <sys/resource.h>

// Host-only: production native engine/template/sampler, synthetic JSONL input.
// It has no Android, Telephony, network, or tool-dispatch capability.
int main(int argc, char ** argv) {
    if (argc != 2) {
        std::cerr << "Usage: inkpilot-model-qualification /path/to/qualified.gguf\n";
        return 2;
    }
    std::string line;
    const inkpilot::Limits limits{2048, 256, 800, 120000, 4};
    while (std::getline(std::cin, line)) {
        try {
            const auto input = nlohmann::json::parse(line);
            const auto id = input.at("id").get<std::string>();
            const auto recipient = input.at("recipient").get<std::string>();
            const auto message = input.at("message").get<std::string>();
            const auto notes = input.at("notes");
            const auto instructions = input.at("instructions").get<std::string>();
            if (!notes.is_array() || notes.size() > 12 || message.size() > 16000 ||
                instructions.size() > 32000 || id.empty() || id.size() > 80) return 3;
            nlohmann::json user = {{"customer_message", message}, {"staff_approved_contact_notes", notes}};
            nlohmann::json result = {{"id", id}, {"recipient", recipient}};
            const auto started = std::chrono::steady_clock::now();
            try {
                const auto reply = inkpilot::generate(argv[1], instructions, user.dump(), limits, [] { return false; });
                result["status"] = "OK";
                result["reply"] = reply;
                result["error_category"] = "NONE";
            } catch (const std::exception & failure) {
                result["status"] = "ERROR";
                result["reply"] = "";
                result["error_category"] = inkpilot::qualification_error_category(failure);
            } catch (...) {
                result["status"] = "ERROR";
                result["reply"] = "";
                result["error_category"] = "OTHER";
            }
            result["elapsed_ms"] = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - started).count();
            struct rusage usage {};
            getrusage(RUSAGE_SELF, &usage);
            result["peak_rss_kb"] = usage.ru_maxrss;
            std::cout << result.dump() << std::endl;
        } catch (const nlohmann::json::exception &) {
            std::cerr << "Invalid synthetic case input\n";
            return 3;
        }
    }
    return 0;
}
