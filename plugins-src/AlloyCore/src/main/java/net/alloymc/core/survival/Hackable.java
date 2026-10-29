package net.alloymc.core.survival;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * An ability the EMP can switch off. Called every tick while the EMP wave grows, with the wave's current radius, so
 * things go down the moment the wave reaches them. Must be safe to call again for things already shut down.
 */
public interface Hackable {
    /**
     * Shut down whatever this ability has running inside the wave (see {@link EmpWave#reaches}). Never touch what
     * {@code caster} owns: the EMP only hits other people's abilities. Returns how many things were shut down.
     */
    int hack(Location center, double radius, Player caster);
}
