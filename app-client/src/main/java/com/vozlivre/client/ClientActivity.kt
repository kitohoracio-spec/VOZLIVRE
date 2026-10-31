package com.vozlivre.client

import com.vozlivre.core.AudioSettingsSection
import com.vozlivre.core.BackgroundSection
import com.vozlivre.core.BaseMainActivity
import com.vozlivre.core.CreditsSection
import com.vozlivre.core.IdentitySection
import com.vozlivre.core.LogoSection
import com.vozlivre.core.PermissionsSection
import com.vozlivre.core.R
import com.vozlivre.core.Screen
import com.vozlivre.core.Tab

/** Interface simples do Cliente: ligar ao servidor, chamar e controlar o áudio. */
class ClientActivity : BaseMainActivity() {
    override fun buildTabs(): List<Tab> = listOf(
        Tab(R.drawable.ic_wifi, "Ligação", Screen(k, listOf(
            LogoSection(k, "Cliente"), ClientConnectionSection(k, engine, this), ServerListSection(k, engine, this),
            OtherMethodsSection(k, engine, this), IdentitySection(k, engine, this)))),
        Tab(R.drawable.ic_call, "Chamadas", Screen(k, listOf(
            header(R.drawable.ic_call, "Chamadas"), ClientCallsSection(k, engine, this)))),
        Tab(R.drawable.ic_speaker, "Áudio", Screen(k, listOf(
            header(R.drawable.ic_speaker, "Áudio"), AudioSettingsSection(k, engine, this)))),
        Tab(R.drawable.ic_bars, "Estado", Screen(k, listOf(
            header(R.drawable.ic_bars, "Estado"), ClientStatusSection(k, engine), PermissionsSection(k, this),
            BackgroundSection(k, engine, this), CreditsSection(k))))
    )
}
