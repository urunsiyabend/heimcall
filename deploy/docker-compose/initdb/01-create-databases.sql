-- Database-per-service: the postgres image creates `incident` (POSTGRES_DB).
-- This script adds the remaining service databases on first init.
-- Note: runs only when the data volume is empty. After adding a DB here,
-- recreate the volume: `docker compose down -v && docker compose up -d`.

CREATE ROLE integration WITH LOGIN PASSWORD 'integration';
CREATE DATABASE integration OWNER integration;

CREATE ROLE identity WITH LOGIN PASSWORD 'identity';
CREATE DATABASE identity OWNER identity;

CREATE ROLE catalog WITH LOGIN PASSWORD 'catalog';
CREATE DATABASE catalog OWNER catalog;

CREATE ROLE schedule WITH LOGIN PASSWORD 'schedule';
CREATE DATABASE schedule OWNER schedule;

CREATE ROLE escalation WITH LOGIN PASSWORD 'escalation';
CREATE DATABASE escalation OWNER escalation;
