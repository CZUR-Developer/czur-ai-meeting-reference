package com.czur.aimeeting.reference.mock;

import com.czur.aimeeting.model.SummaryAdapter;
import com.czur.aimeeting.model.SummaryInput;
import com.czur.aimeeting.model.SummaryResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 无外部模型的固定文本总结样例，只用于验证标准纪要提交与结果读回链路。 */
public final class MockSummaryAdapter implements SummaryAdapter {
    public static final String ID = "mock-summary";

    @Override public String id() { return ID; }

    @Override public String requestBody(SummaryInput input) {
        try {
            return new JSONObject().put("language", input.language())
                    .put("transcript", input.transcript()).toString();
        } catch (JSONException invalid) {
            throw new IllegalStateException("无法编码模拟总结请求", invalid);
        }
    }

    @Override public SummaryResult parseResponse(String response, String language)
            throws IOException {
        try {
            JSONObject body = new JSONObject(response);
            return new SummaryResult(language, body.getString("title"),
                    body.getString("overview"), strings(body.getJSONArray("keyPoints")),
                    strings(body.getJSONArray("actionItems")));
        } catch (JSONException invalid) {
            throw new IOException("模拟总结响应格式错误", invalid);
        }
    }

    private static List<String> strings(JSONArray values) throws JSONException {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            result.add(values.getString(index));
        }
        return result;
    }
}
