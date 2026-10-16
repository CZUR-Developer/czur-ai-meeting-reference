package com.czur.aimeeting.client

import com.czur.aimeeting.model.ModelAdapter

/** 构建期模型解析器工厂，每次创建独立状态；音源、连接、Server 与显示路由由组合层管理。 */
fun interface ModelAdapterFactory {
    fun create(): ModelAdapter
}
