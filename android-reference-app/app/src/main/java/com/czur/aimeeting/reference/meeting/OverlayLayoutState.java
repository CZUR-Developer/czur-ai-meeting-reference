package com.czur.aimeeting.reference.meeting;

/**
 * 一场悬浮窗的轻量布局状态。收起只改变窗口呈现，保存原布局以便恢复；
 * 原文、译文、时长与 Stop 继续由会议 Service 持有，不在这里复制会议状态。
 */
public final class OverlayLayoutState {
    public enum Type { SUBTITLE, TRANSLATION_NORMAL, TRANSLATION_LARGE, MINI }

    private Type current;
    private Type beforeMini;

    public OverlayLayoutState(boolean bilingual) {
        current = bilingual ? Type.TRANSLATION_NORMAL : Type.SUBTITLE;
        beforeMini = current;
    }

    public Type current() { return current; }

    public void collapse() {
        if (current == Type.MINI) return;
        beforeMini = current;
        current = Type.MINI;
    }

    public void restore() {
        if (current == Type.MINI) current = beforeMini;
    }

    public void showLarge() {
        if (current == Type.TRANSLATION_NORMAL) current = Type.TRANSLATION_LARGE;
    }

    public void showNormal() {
        if (current == Type.TRANSLATION_LARGE) current = Type.TRANSLATION_NORMAL;
    }
}
