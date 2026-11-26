package com.damn.mcpbox.shizuku;

interface IShellUserService {
    void destroy() = 16777114;
    int launchActivityOnDisplay(String componentName, int displayId) = 1;
    int runInputCommand(in String[] args) = 2;
}
