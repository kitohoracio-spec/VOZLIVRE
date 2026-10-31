package com.vozlivre.core

/** Implementado pela Application de cada APK para dar acesso ao motor de chamadas. */
interface EngineHost {
    val engine: CallEngine
}
