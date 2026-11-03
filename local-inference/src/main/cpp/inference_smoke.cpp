#include "inference_engine.h"
#include <iostream>

int main(int argc, char ** argv) {
    if (argc != 2) {
        std::cerr << "Usage: inkpilot-inference-smoke /path/to/LFM3.gguf\n";
        return 2;
    }
    try {
        const inkpilot::Limits limits{1024, 128, 800, 180000, 4};
        const auto reply = inkpilot::generate(argv[1],
            "Draft one short tattoo studio reply for staff review. Use supplied facts only. Return only reply text.",
            "{\"customer_message\":\"Can I book a rose tattoo consultation?\","
            "\"staff_approved_contact_notes\":[\"Ask which day the client prefers.\"]}",
            limits, [] { return false; });
        if (reply.empty() || reply.find("<think>") != std::string::npos || reply.find("<tool_call>") != std::string::npos) {
            throw std::runtime_error("Expected final text only");
        }
        std::cout << "PASS: real LFM3 generated a held-reply candidate\n" << reply << '\n';
        return 0;
    } catch (const std::exception & error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
