package com.lightningbi.lightning_engine.config

import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.service.RegistryService
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

@Component
class DemoDataSeeder(
    private val registryService: RegistryService,
    private val registryRepository: RegistryRepository
) : CommandLineRunner {

    override fun run(vararg args: String) {
        val existing = registryRepository.findAreaByNome("demo_vendite")
        if (existing != null) return

        val area = registryService.createArea("demo_vendite", "ch_lbi_demo_vendite")

        val dimCliente = registryService.createDimensione(
            "cliente_demo", "string", conformata = false, tabellaDim = null, colonnaChiave = null
        )
        val dimProdotto = registryService.createDimensione(
            "prodotto_demo", "string", conformata = false, tabellaDim = null, colonnaChiave = null
        )

        registryService.linkDimensioneToArea(area.id, dimCliente.id, "cliente_id", obbligatoria = true, cardinalita = 100)
        registryService.linkDimensioneToArea(area.id, dimProdotto.id, "prodotto_id", obbligatoria = true, cardinalita = 50)

        registryService.addMetrica(area.id, "fatturato", "importo", "SUM")

        println("DEMO AREA CREATED: areaId=${area.id}")
    }
}