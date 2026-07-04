package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.EtlRun

interface EtlRunRepository {
    fun save(run: EtlRun): EtlRun
    fun update(run: EtlRun)
}