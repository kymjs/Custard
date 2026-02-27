#include "StreamPlanExecutionPlugin.h"

namespace streamnative {

StreamPlanExecutionPlugin::StreamPlanExecutionPlugin(bool includeTagsInOutput)
        : includeTagsInOutput_(includeTagsInOutput),
          state_(PluginState::IDLE),
          allowStartAfterEndTag_(false),
          startState_(StartState::IDLE),
          startMatchIndex_(0),
          endInited_(false) {
    endMatcher_.setPattern(u"</plan>");
    endInited_ = true;
    reset();
}

PluginState StreamPlanExecutionPlugin::state() const {
    return state_;
}

bool StreamPlanExecutionPlugin::initPlugin() {
    reset();
    return true;
}

void StreamPlanExecutionPlugin::resetInternal() {
    state_ = PluginState::IDLE;
    startState_ = StartState::IDLE;
    startMatchIndex_ = 0;
    endMatcher_.reset();
}

void StreamPlanExecutionPlugin::reset() {
    resetInternal();
}

bool StreamPlanExecutionPlugin::processChar(char16_t c, bool atStartOfLine) {
    (void)atStartOfLine;

    if (state_ == PluginState::PROCESSING) {
        if (endInited_ && endMatcher_.process(c)) {
            allowStartAfterEndTag_ = true;
            resetInternal();
            return includeTagsInOutput_;
        }
        return true;
    }

    if (state_ == PluginState::IDLE && !atStartOfLine) {
        if (!allowStartAfterEndTag_) {
            return true;
        }
        if (c == u' ' || c == u'\t') {
            return true;
        }
    }

    // Start tag matching: "<plan" then arbitrary attrs until '>'
    if (startState_ == StartState::IDLE) {
        if (c == u'<') {
            startState_ = StartState::MATCHING;
            startMatchIndex_ = 1; // matched '<'
            state_ = PluginState::TRYING;
            return includeTagsInOutput_;
        }
        return true;
    }

    if (startState_ == StartState::MATCHING) {
        // We need to match "<plan" then read until '>'
        static const char16_t kLit[] = {u'<', u'p', u'l', u'a', u'n'};
        if (startMatchIndex_ < 5) {
            if (c == kLit[startMatchIndex_]) {
                startMatchIndex_++;
                state_ = (startMatchIndex_ == 5) ? PluginState::TRYING : PluginState::TRYING;
                return includeTagsInOutput_;
            }
            // fail
            resetInternal();
            allowStartAfterEndTag_ = false;
            return true;
        }

        // After "<plan" matched, consume until '>'
        if (c == u'>') {
            state_ = PluginState::PROCESSING;
            startState_ = StartState::IDLE;
            startMatchIndex_ = 0;
            allowStartAfterEndTag_ = false;
            endMatcher_.reset();
            return includeTagsInOutput_;
        }

        state_ = PluginState::TRYING;
        return includeTagsInOutput_;
    }

    return true;
}

} // namespace streamnative
