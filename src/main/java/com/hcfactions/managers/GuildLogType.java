package com.hcfactions.managers;

/**
 * Types of events tracked in the guild activity log.
 */
public enum GuildLogType {
    MEMBER_JOIN,
    MEMBER_LEAVE,
    MEMBER_KICK,
    MEMBER_PROMOTE,
    MEMBER_DEMOTE,
    CHUNK_CLAIM,
    CHUNK_UNCLAIM,
    HOME_SET,
    PERMISSION_CHANGE,
    GUILD_CREATE,
    BANK_DEPOSIT,
    BANK_WITHDRAW,
    CLAIM_DECAY
}
