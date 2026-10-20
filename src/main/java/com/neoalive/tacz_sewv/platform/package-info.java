/**
 * NeoForge port branch: thin stand-ins for the Forge 1.20.1 platform APIs this mod used across many
 * files (networking, chunk tickets, side dispatch). Each mirrors the Forge call shape it replaces,
 * so a feature merged forward from the Forge {@code main} branch usually needs only its imports
 * swapped. New code on this branch calls these, never the NeoForge APIs they wrap.
 */
package com.neoalive.tacz_sewv.platform;
