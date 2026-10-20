package org.crafterscr.crafterscollect;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/** Entrada server-side. No registra bloques, paquetes propios ni listeners cliente. */
@Mod(CraftersCollect.MOD_ID)
public final class CraftersCollect {
    public static final String MOD_ID = "crafterscollect";

    public CraftersCollect() {
        NeoForge.EVENT_BUS.addListener(CollectCommands::register);
        NeoForge.EVENT_BUS.addListener(CollectService::onServerTick);
        NeoForge.EVENT_BUS.addListener(CollectService::onLogout);
        NeoForge.EVENT_BUS.addListener(CollectService::onLogin);
        NeoForge.EVENT_BUS.addListener(CollectService::onServerStopped);
    }
}
