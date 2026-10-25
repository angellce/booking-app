-- A time slot published by the host.
CREATE TABLE slot (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    start_at         TIMESTAMPTZ NOT NULL,
    end_at           TIMESTAMPTZ NOT NULL,
    duration_minutes INTEGER     NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT slot_duration_range CHECK (duration_minutes BETWEEN 5 AND 480),
    CONSTRAINT slot_end_matches_duration CHECK (end_at = start_at + make_interval(mins => duration_minutes)),
    -- No two slots may overlap. The range is half-open [start, end), so back-to-back slots
    -- (one ending at 10:00, the next starting at 10:00) are allowed.
    CONSTRAINT slot_no_overlap EXCLUDE USING gist (tstzrange(start_at, end_at, '[)') WITH &&)
);

CREATE INDEX slot_start_at_idx ON slot (start_at);

-- A visitor's booking. UNIQUE(slot_id) guarantees at most one booking per slot,
-- even when concurrent requests race: only one INSERT can commit.
CREATE TABLE booking (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slot_id    BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    email      VARCHAR(254) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT booking_slot_unique UNIQUE (slot_id),
    CONSTRAINT booking_slot_fk FOREIGN KEY (slot_id) REFERENCES slot (id) ON DELETE CASCADE
);
