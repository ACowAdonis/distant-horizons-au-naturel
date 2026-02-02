
ALTER TABLE DhFullData RENAME TO Legacy_FullData_V1;

--batch--

ALTER TABLE Legacy_FullData_V1 ADD COLUMN MigrationFailed BIT NOT NULL DEFAULT 0;

--batch--

CREATE TABLE FullData (
    -- compound primary key
     DetailLevel TINYINT NOT NULL -- LOD detail level, not section detail level IE 0, 1, 2 not 6, 7, 8
    ,PosX INT NOT NULL
    ,PosZ INT NOT NULL

    ,MinY INT NOT NULL
    ,DataChecksum INT NOT NULL

    ,Data BLOB NULL
    ,ColumnWorldCompressionMode BLOB NULL
    ,Mapping BLOB NULL

    ,DataFormatVersion TINYINT NULL
    ,CompressionMode TINYINT NULL

    ,ApplyToParent BIT NULL

    ,IsComplete BIT NULL -- whether the section has all 4096 columns populated

    ,LastModifiedUnixDateTime BIGINT NOT NULL -- in GMT 0
    ,CreatedUnixDateTime BIGINT NOT NULL -- in GMT 0

    ,PRIMARY KEY (DetailLevel, PosX, PosZ)
);
