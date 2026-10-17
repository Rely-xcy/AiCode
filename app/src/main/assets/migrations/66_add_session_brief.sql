CREATE TABLE IF NOT EXISTS `session_briefs` (
    `sessionId` TEXT NOT NULL,
    `content` TEXT NOT NULL,
    `updatedAt` INTEGER NOT NULL,
    PRIMARY KEY(`sessionId`)
);
