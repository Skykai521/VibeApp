package com.vibe.app.plugin;

interface IPluginInspector {
    String dumpViewTree();
    String performClick(String selector);
    String inputText(String selector, String text);
    String scroll(String selector, String direction, int amount);
    String performGesture(String gestureJson);
}
