#pragma once

#include <string>

#include "../StreamKmpGraph.h"
#include "StreamPlugin.h"

namespace streamnative {

class StreamPlanExecutionPlugin final : public StreamPlugin {
public:
    explicit StreamPlanExecutionPlugin(bool includeTagsInOutput = true);

    PluginState state() const override;
    bool processChar(char16_t c, bool atStartOfLine) override;
    bool initPlugin() override;
    void reset() override;

private:
    bool includeTagsInOutput_;
    PluginState state_;

    bool allowStartAfterEndTag_;

    enum class StartState {
        IDLE,
        MATCHING,
    };

    StartState startState_;
    int startMatchIndex_;

    KmpMatcher endMatcher_;
    bool endInited_;

    void resetInternal();
};

} // namespace streamnative
