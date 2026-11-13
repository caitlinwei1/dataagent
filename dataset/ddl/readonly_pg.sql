-- Read-only role for PostgreSQL / Hologres stand-in (local demo password; change in any real deploy).
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gbi_reader') THEN
    CREATE ROLE gbi_reader LOGIN PASSWORD 'gbi_reader_local';
  END IF;
END $$;
GRANT USAGE ON SCHEMA gbi_ecommerce TO gbi_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA gbi_ecommerce TO gbi_reader;
ALTER ROLE gbi_reader SET default_transaction_read_only = on;
ALTER ROLE gbi_reader SET statement_timeout = '30s';
ALTER ROLE gbi_reader SET search_path = gbi_ecommerce;
