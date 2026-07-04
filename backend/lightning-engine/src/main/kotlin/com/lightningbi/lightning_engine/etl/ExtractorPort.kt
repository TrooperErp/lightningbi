package com.lightningbi.lightning_engine.etl

interface ExtractorPort {
    fun extract(config: Map<String, Any>, lastSync: String?): Sequence<Map<String, Any?>>
}