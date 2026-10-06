package com.czur.aimeeting.client.provider;

import com.czur.aimeeting.model.SummaryAdapter;
import com.czur.aimeeting.model.SummaryInput;
import com.czur.aimeeting.model.SummaryResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 百炼应用 completion 的公开文本协议适配；不持有 Key、AppId 或会议状态。 */
public final class BailianSummaryAdapter implements SummaryAdapter {
    public static final String ID = "bailian-summary";

    @Override public String id() { return ID; }

    @Override public String requestBody(SummaryInput input) {
        String prompt = "请根据以下已经确认的最终字幕生成会议纪要。仅输出一个 JSON 对象，"
                + "不要 Markdown 或解释。字段必须为 title、overview、keyPoints、actionItems；"
                + "后两项是字符串数组，没有明确待办时给空数组，不要臆造。\n"
                + "语言：" + input.language() + "\n最终字幕：\n" + input.transcript();
        try {
            return new JSONObject().put("input", new JSONObject().put("prompt", prompt))
                    .put("parameters", new JSONObject()).toString();
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("百炼总结请求无法编码");
        }
    }

    @Override public SummaryResult parseResponse(String response, String language)
            throws IOException {
        try {
            String text = new JSONObject(response).getJSONObject("output")
                    .getString("text").trim();
            if (text.startsWith("```")) {
                int firstLine = text.indexOf('\n');
                int lastFence = text.lastIndexOf("```");
                if (firstLine < 0 || lastFence <= firstLine) throw new JSONException("fence");
                text = text.substring(firstLine + 1, lastFence).trim();
            }
            JSONObject summary = new JSONObject(text);
            return new SummaryResult(language,
                    required(summary.getString("title"), 512),
                    required(summary.getString("overview"), 16_384),
                    items(summary.getJSONArray("keyPoints")),
                    items(summary.getJSONArray("actionItems")));
        } catch (JSONException | IllegalArgumentException invalid) {
            // 厂商正文可能含用户转写，不附加 cause 或原文到日志/界面。
            throw new IOException("百炼纪要响应格式错误");
        }
    }

    private static String required(String text, int maxLength) {
        String value = text.trim();
        if (value.isEmpty() || value.length() > maxLength) {
            throw new IllegalArgumentException("summary text");
        }
        return value;
    }

    private static List<String> items(JSONArray values) throws JSONException {
        if (values.length() > 100) throw new IllegalArgumentException("summary items");
        List<String> items = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            items.add(required(values.getString(index), 4096));
        }
        return items;
    }
}
