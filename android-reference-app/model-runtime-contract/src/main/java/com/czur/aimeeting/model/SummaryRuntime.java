package com.czur.aimeeting.model;

import java.io.IOException;

/** Android SDK 执行一次短期授权的文本总结请求，原始响应只交给 Adapter 解析。 */
public interface SummaryRuntime {
    SummaryResult summarize(SummarySessionGrant grant, SummaryAdapter adapter,
            SummaryInput input) throws IOException;
}
