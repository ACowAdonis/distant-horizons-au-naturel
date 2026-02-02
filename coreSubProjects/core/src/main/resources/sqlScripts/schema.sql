PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;

--batch--

CREATE TABLE IF NOT EXISTS FullData (
    DetailLevel TINYINT NOT NULL,
    PosX INT NOT NULL,
    PosZ INT NOT NULL,
    Data BLOB NULL,
    ColumnWorldCompressionMode BLOB NULL,
    Mapping BLOB NULL,
    NorthAdjData BLOB NULL,
    SouthAdjData BLOB NULL,
    EastAdjData BLOB NULL,
    WestAdjData BLOB NULL,
    CompressionMode TINYINT NULL,
    ApplyToParent BIT NULL,
    IsComplete BIT NULL,
    LastModifiedUnixDateTime BIGINT NOT NULL,
    CreatedUnixDateTime BIGINT NOT NULL,
    PRIMARY KEY (DetailLevel, PosX, PosZ)
)

--batch--

CREATE INDEX IF NOT EXISTS idx_apply_parent ON FullData(ApplyToParent, DetailLevel, PosX, PosZ)
WHERE ApplyToParent = 1

--batch--

CREATE INDEX IF NOT EXISTS idx_detail_complete ON FullData(DetailLevel, PosX, PosZ, IsComplete)

--batch--

CREATE INDEX IF NOT EXISTS idx_timestamp_range ON FullData(DetailLevel, PosX, PosZ, LastModifiedUnixDateTime)

--batch--

CREATE TABLE IF NOT EXISTS ChunkHash (
    ChunkPosX INT NOT NULL,
    ChunkPosZ INT NOT NULL,
    ChunkHash INT NOT NULL,
    LastModifiedUnixDateTime BIGINT NOT NULL,
    CreatedUnixDateTime BIGINT NOT NULL,
    PRIMARY KEY (ChunkPosX, ChunkPosZ)
)

--batch--

CREATE TABLE IF NOT EXISTS BeaconBeam (
    BlockPosX INT NOT NULL,
    BlockPosY INT NOT NULL,
    BlockPosZ INT NOT NULL,
    ColorR INT NOT NULL,
    ColorG INT NOT NULL,
    ColorB INT NOT NULL,
    LastModifiedUnixDateTime BIGINT NOT NULL,
    CreatedUnixDateTime BIGINT NOT NULL,
    PRIMARY KEY (BlockPosX, BlockPosY, BlockPosZ)
)
