package net.alloymc.core.arcade;

import net.alloymc.core.arcade.core.Arcade;

/** Every arcade game mode. To add one: write its ArcadeGame + Match in arcade.games.&lt;mode&gt; and register it here. */
public final class Games {
    private Games() {}

    public static java.util.List<net.alloymc.core.arcade.core.ArcadeGame> all() {
        java.util.List<net.alloymc.core.arcade.core.ArcadeGame> l = new java.util.ArrayList<>();
        registerInto(l::add);
        return l;
    }

    static void registerAll(Arcade a) { registerInto(a::register); }

    private static void registerInto(java.util.function.Consumer<net.alloymc.core.arcade.core.ArcadeGame> a) {
        a.accept(new net.alloymc.core.arcade.games.spleef.Spleef());
        a.accept(new net.alloymc.core.arcade.games.tntrun.TntRun());
        a.accept(new net.alloymc.core.arcade.games.infected.Infected());
        a.accept(new net.alloymc.core.arcade.games.bedwars.BedWars());
        a.accept(new net.alloymc.core.arcade.games.skywars.SkyWars());
        a.accept(new net.alloymc.core.arcade.games.duels.Duels());
        a.accept(new net.alloymc.core.arcade.games.parkour.Parkour());
        a.accept(new net.alloymc.core.arcade.games.dropper.Dropper());
        a.accept(new net.alloymc.core.arcade.games.blockparty.BlockParty());
        a.accept(new net.alloymc.core.arcade.games.tnttag.TntTag());
        a.accept(new net.alloymc.core.arcade.games.bridge.Bridge());
        a.accept(new net.alloymc.core.arcade.games.kitpvp.KitPvp());
        a.accept(new net.alloymc.core.arcade.games.uhc.MiniUhc());
        a.accept(new net.alloymc.core.arcade.games.murder.MurderMystery());
        a.accept(new net.alloymc.core.arcade.games.hideseek.HideAndSeek());
        a.accept(new net.alloymc.core.arcade.games.buildbattle.BuildBattle());
        a.accept(new net.alloymc.core.arcade.games.speedbuilders.SpeedBuilders());
    }
}
