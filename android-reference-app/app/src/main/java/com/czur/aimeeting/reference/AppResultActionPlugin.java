package com.czur.aimeeting.reference;

import android.content.Context;
import android.view.View;
import com.czur.aimeeting.client.result.ResultActionPlugin;
import java.util.function.Supplier;

/**
 * App结果动作的薄UI边界，公共core不依赖Android。构建期在MeetingAppAssembly注册。
 * 页面只呈现View并在会前Hook调用freeze一次；返回的core插件只持有不可变参数及应用Context，
 * 不得捕获Activity／View。设置对象留页面，Service只消费准备后的执行闭包。
 */
public interface AppResultActionPlugin extends ResultActionPlugin {
    /** Android主线程创建设置；无输入动作可返回空View，不出现未配置的占位入口。 */
    Settings createSettings(Context pageContext);

    /** freeze在点击开始时读取本地输入；不得同步网络请求，也不序列化任意用户参数。 */
    record Settings(View view, Supplier<ResultActionPlugin> freeze) { }

    /**
     * 已准备、需要可见页面的通用操作标记；交context.uiExecutor，由App同进程内存宿主暂存。
     * Service退出不清该交接，Activity可见后消费一次；不承诺进程恢复，也不后台强拉页面。
     * 只捕获应用Context／冻结参数及完成回调，不捕获Activity／View，不重新访问已关闭Session。
     * 普通编排／完成回调仍走主线程；实际显示后才成功，旧宿主关闭不吞掉完成闭包。
     */
    record UiOperation(Runnable command) implements Runnable {
        @Override public void run() { command.run(); }
    }
}
