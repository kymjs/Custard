package com.kymjs.ai.custard.terminal;

import com.kymjs.ai.custard.terminal.CommandExecutionEvent;
import com.kymjs.ai.custard.terminal.SessionDirectoryEvent;

oneway interface ITerminalCallback {
    void onCommandExecutionUpdate(in CommandExecutionEvent event);
    void onSessionDirectoryChanged(in SessionDirectoryEvent event);
} 