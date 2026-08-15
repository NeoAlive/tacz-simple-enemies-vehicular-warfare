package com.neoalive.tacz_sewv.entity.ai.command.platoon;

/**
 * Standing orders a Commander may autonomously issue to its own platoon
 * ({@link CommanderOrderDispatch}). Deliberately small and addressable — a hook surface for future
 * order types, not a finished behaviour catalogue.
 */
public enum CommanderOrderType {
    SEARCH_AND_DESTROY,
    PATROL,
    RAPPEL
}
