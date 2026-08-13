-- V9__add_grievance_email_to_banks.sql
-- Add optional grievance/nodal officer email for complaint mailto: links.
-- NULL means no publicly known email — the UI hides the "Open Email Draft" button.

ALTER TABLE banks ADD COLUMN grievance_email TEXT;
