package com.czur.aimeeting.reference.sample;

import com.czur.aimeeting.model.ModelAdapter;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSignal;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranscriptListener;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 第二种模型消息格式的公开样例。与听悟不同，它使用顶层 type/segment/revision
 * 字段；网络、麦克风、会议事件提交仍由同一 SDK 和会议流程处理。
 *
 * <p>样例模型服务在收到 start 后回复 ready；收到二进制 PCM16 帧后可反复发送
 * transcript，其中同一 segment 的 revision 递增，final=true 表示确认；
 * 收到 stop 后先发最终字幕，最后发 finished。此格式只声明实时字幕能力。
 */
public final class SampleJsonModelAdapter implements ModelAdapter {
    public static final String ID = "sample-json-ws";

    private final Map<String, Integer> revisions = new HashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String startMessage(ModelSessionGrant grant) {
        return "{\"type\":\"start\"}";
    }

    @Override
    public String stopMessage(ModelSessionGrant grant) {
        return "{\"type\":\"stop\"}";
    }

    @Override
    public ModelSignal onServerMessage(String message, TranscriptListener listener) {
        try {
            JSONObject json = new JSONObject(message);
            return switch (json.getString("type")) {
                case "ready" -> ModelSignal.AUDIO_READY;
                case "finished" -> ModelSignal.FINISHED;
                case "transcript" -> {
                    String segment = json.getString("segment");
                    int revision = json.getInt("revision");
                    if (revision > revisions.getOrDefault(segment, 0)) {
                        long startMs = json.getLong("startMs");
                        long endMs = json.getLong("endMs");
                        listener.onTranscript(new TranscriptEvent(segment, revision,
                                startMs, endMs, json.getString("language"),
                                json.getString("text"), json.getBoolean("final")));
                        revisions.put(segment, revision);
                    }
                    yield ModelSignal.NONE;
                }
                default -> ModelSignal.NONE;
            };
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("样例模型消息格式错误", malformed);
        }
    }
}
