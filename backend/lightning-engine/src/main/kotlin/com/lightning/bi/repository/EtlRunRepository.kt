package com.lightning.bi.repository

import com.lightning.bi.model.EtlRun

interface EtlRunRepository {
    fun save(run: EtlRun): EtlRun
    fun update(run: EtlRun)
}