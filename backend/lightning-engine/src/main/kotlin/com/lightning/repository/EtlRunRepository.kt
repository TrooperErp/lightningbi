package com.lightning.repository

import com.lightning.model.EtlRun

interface EtlRunRepository {
    fun save(run: EtlRun): EtlRun
    fun update(run: EtlRun)
}