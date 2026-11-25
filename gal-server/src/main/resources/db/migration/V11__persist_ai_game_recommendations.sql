-- Execute manually with AI_GAME_TOOLS_ENABLED=false in both services.
-- MySQL DDL commits implicitly. Back up ai_message before execution.
ALTER TABLE ai_message ADD COLUMN recommendations JSON NULL DEFAULT NULL;
