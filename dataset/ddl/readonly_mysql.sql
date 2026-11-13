-- Read-only account used by the agent / connector to query business data (local demo password; change in any real deploy).
CREATE USER IF NOT EXISTS 'gbi_reader'@'%' IDENTIFIED BY 'gbi_reader_local';
GRANT SELECT, SHOW VIEW ON gbi_ecommerce.* TO 'gbi_reader'@'%';
ALTER USER 'gbi_reader'@'%' WITH MAX_USER_CONNECTIONS 20;
FLUSH PRIVILEGES;
