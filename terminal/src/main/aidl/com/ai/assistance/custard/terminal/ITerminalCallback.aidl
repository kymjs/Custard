package com.ai.assistance.custard.terminal;

import com.ai.assistance.custard.terminal.CommandExecutionEvent;
import com.ai.assistance.custard.terminal.SessionDirectoryEvent;

oneway interface ITerminalCallback {
    void onCommandExecutionUpdate(in CommandExecutionEvent event);
    void onSessionDirectoryChanged(in SessionDirectoryEvent event);
} 