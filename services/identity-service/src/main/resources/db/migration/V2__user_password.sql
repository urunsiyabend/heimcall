-- Real auth (Phase 7 ticket 2). Password is BCrypt-hashed at rest. Nullable so users created before
-- auth existed remain valid rows; they simply cannot log in until a password is set.
ALTER TABLE app_user ADD COLUMN password_hash VARCHAR(100);
