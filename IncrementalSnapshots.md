Snapshot 1 and 2 have different files

OLD
```text
/var/folders/km/nt7_12rn6rx4m3fs2kttr2c00000gq/T/junit-17559797703837138282
├── index-0
├── index.latest
├── indices
│   └── WbqWmq1SSwuVhNy-Qwbuqg
│       ├── 0
│       │   ├── __2wEk_e49Sga2tYOtVKKXbg
│       │   ├── __wgaOHsvoSFq3MEW145cRrQ
│       │   ├── index-eXd4Cfv5QTieZuKJN8AAxA
│       │   └── snap-JbOHVks5TF-mHC8O1ep9vw.dat
│       └── meta-6XR7ypYBvO2MINKAQm4w.dat
├── meta-JbOHVks5TF-mHC8O1ep9vw.dat
└── snap-JbOHVks5TF-mHC8O1ep9vw.dat

4 directories, 9 files
```

NEW
```text
/var/folders/km/nt7_12rn6rx4m3fs2kttr2c00000gq/T/junit-5411672040453325157
├── index-1
├── index.latest
├── indices
│   └── WbqWmq1SSwuVhNy-Qwbuqg
│       ├── 0
│       │   ├── __2wEk_e49Sga2tYOtVKKXbg
│       │   ├── __5mxLpJUDQ2GURRMuTWGkWg
│       │   ├── __5XYCMt1cSeqJ6i1CxWbQFA
│       │   ├── __bb4m4lTdTjaCvjVHMFcIPA
│       │   ├── __qYlFJb_KRPyI5ZwQST26Pg
│       │   ├── __wgaOHsvoSFq3MEW145cRrQ
│       │   ├── __YvcTWOjFSeqJy1j7fD658w
│       │   ├── index-eH05W_5OTrKD7CvXli2ZBA
│       │   ├── snap-JbOHVks5TF-mHC8O1ep9vw.dat
│       │   └── snap-pOT0RxW5Q5iEVgGvo-AFkg.dat
│       └── meta-6XR7ypYBvO2MINKAQm4w.dat
├── meta-JbOHVks5TF-mHC8O1ep9vw.dat
├── meta-pOT0RxW5Q5iEVgGvo-AFkg.dat
├── snap-JbOHVks5TF-mHC8O1ep9vw.dat
└── snap-pOT0RxW5Q5iEVgGvo-AFkg.dat

4 directories, 17 files
```

When we assmeble the repo data, this comes out as
OLD:
```json
{
  "name" : "initial_snapshot",
  "index_version" : 3,
  "start_time" : 1747153797487,
  "time" : 0,
  "number_of_files" : 4,
  "total_size" : 4362,
  "files" : [ {
    "name" : "__wgaOHsvoSFq3MEW145cRrQ",
    "physical_name" : "_0.cfe",
    "length" : 479,
    "checksum" : "47gts8",
    "part_size" : 9223372036854775807,
    "written_by" : "8.7.0"
  }, {
    "name" : "v__lbmkDrQoTgWGgl3Vjz1EyA",
    "physical_name" : "_0.si",
    "length" : 369,
    "checksum" : "ucyy89",
    "part_size" : 9223372036854775807,
    "written_by" : "8.7.0",
    "meta_hash" : "P9dsFxNMdWNlbmU4NlNlZ21lbnRJbmZvAAAAAIezWJt1VPhvEXQ1ddEbjKoAAAAACAAAAAcAAAAAAQAAAAgAAAAHAAAAAAAAAAIBCgJvcwVMaW51eAtqYXZhLnZlbmRvcgxBZG9wdE9wZW5KREsMamF2YS52ZXJzaW9uBjE1LjAuMQ9qYXZhLnZtLnZlcnNpb24IMTUuMC4xKzkObHVjZW5lLnZlcnNpb24FOC43LjAHb3MuYXJjaAdhYXJjaDY0FGphdmEucnVudGltZS52ZXJzaW9uCDE1LjAuMSs5BnNvdXJjZQVmbHVzaApvcy52ZXJzaW9uEDYuMTAuMTQtbGludXhraXQJdGltZXN0YW1wDTE3NDcxNTM3OTY4NjADBl8wLmNmZQVfMC5zaQZfMC5jZnMBH0x1Y2VuZTg3U3RvcmVkRmllbGRzRm9ybWF0Lm1vZGUKQkVTVF9TUEVFRADAKJPoAAAAAAAAAABta6XJ"
  }, {
    "name" : "__2wEk_e49Sga2tYOtVKKXbg",
    "physical_name" : "_0.cfs",
    "length" : 3201,
    "checksum" : "w612qz",
    "part_size" : 9223372036854775807,
    "written_by" : "8.7.0"
  }, {
    "name" : "v__6mCk_dhaRsOv6GacK1ig1A",
    "physical_name" : "segments_3",
    "length" : 313,
    "checksum" : "lp9mt7",
    "part_size" : 9223372036854775807,
    "written_by" : "8.7.0",
    "meta_hash" : "P9dsFwhzZWdtZW50cwAAAAqHs1ibdVT4bxF0NXXRG4yyATMIBwAIAAAAAAAAAAkBAAAAAQgHAAJfMIezWJt1VPhvEXQ1ddEbjKoITHVjZW5lODf//////////wAAAAD/////////////////////AAAAAAGHs1ibdVT4bxF0NXXRG4ysAAAAAAAGEGxvY2FsX2NoZWNrcG9pbnQBMRxtYXhfdW5zYWZlX2F1dG9faWRfdGltZXN0YW1wAi0xDXRyYW5zbG9nX3V1aWQWMG9xV2JXZ3dTb1NidnJSZkpRUEtsURNtaW5fcmV0YWluZWRfc2VxX25vATAMaGlzdG9yeV91dWlkFlNYZ2ZXeGc3U0pTZzljNTNBZzZHOFEKbWF4X3NlcV9ubwExwCiT6AAAAAAAAAAATjcIuw=="
  } ]
}
```

NEW:
```json
{
  "snapshotName" : "initial_snapshot2",
  "indexName" : "blog_2023",
  "indexId" : "WbqWmq1SSwuVhNy-Qwbuqg",
  "shardId" : 0,
  "indexVersion" : 4,
  "startTime" : 1747153798894,
  "time" : 0,
  "numberOfFiles" : 7,
  "totalSizeBytes" : 6126,
  "files" : [ {
    "name" : "__bb4m4lTdTjaCvjVHMFcIPA",
    "physicalName" : "_1.cfs",
    "length" : 3556,
    "checksum" : "dyc1nt",
    "partSize" : 9223372036854775807,
    "numberOfParts" : 1,
    "writtenBy" : "8.7.0",
    "metaHash" : null
  }, {
    "name" : "__wgaOHsvoSFq3MEW145cRrQ",
    "physicalName" : "_0.cfe",
    "length" : 479,
    "checksum" : "47gts8",
    "partSize" : 9223372036854775807,
    "numberOfParts" : 1,
    "writtenBy" : "8.7.0",
    "metaHash" : null
  }, {
    "name" : "v__lbmkDrQoTgWGgl3Vjz1EyA",
    "physicalName" : "_0.si",
    "length" : 369,
    "checksum" : "ucyy89",
    "partSize" : 9223372036854775807,
    "numberOfParts" : 1,
    "writtenBy" : "8.7.0",
    "metaHash" : {
      "bytes" : "P9dsFxNMdWNlbmU4NlNlZ21lbnRJbmZvAAAAAIezWJt1VPhvEXQ1ddEbjKoAAAAACAAAAAcAAAAAAQAAAAgAAAAHAAAAAAAAAAIBCgJvcwVMaW51eAtqYXZhLnZlbmRvcgxBZG9wdE9wZW5KREsMamF2YS52ZXJzaW9uBjE1LjAuMQ9qYXZhLnZtLnZlcnNpb24IMTUuMC4xKzkObHVjZW5lLnZlcnNpb24FOC43LjAHb3MuYXJjaAdhYXJjaDY0FGphdmEucnVudGltZS52ZXJzaW9uCDE1LjAuMSs5BnNvdXJjZQVmbHVzaApvcy52ZXJzaW9uEDYuMTAuMTQtbGludXhraXQJdGltZXN0YW1wDTE3NDcxNTM3OTY4NjADBl8wLmNmZQVfMC5zaQZfMC5jZnMBH0x1Y2VuZTg3U3RvcmVkRmllbGRzRm9ybWF0Lm1vZGUKQkVTVF9TUEVFRADAKJPoAAAAAAAAAABta6XJ",
      "offset" : 0,
      "length" : 369,
      "valid" : true
    }
  }, {
    "name" : "__qYlFJb_KRPyI5ZwQST26Pg",
    "physicalName" : "_1.cfe",
    "length" : 479,
    "checksum" : "1ho8dbn",
    "partSize" : 9223372036854775807,
    "numberOfParts" : 1,
    "writtenBy" : "8.7.0",
    "metaHash" : null
  }, {
    "name" : "v__gxa7DCLLS5abXpYD9i_xcA",
    "physicalName" : "_1.si",
    "length" : 369,
    "checksum" : "n5g7qm",
    "partSize" : 9223372036854775807,
    "numberOfParts" : 1,
    "writtenBy" : "8.7.0",
    "metaHash" : {
      "bytes" : "P9dsFxNMdWNlbmU4NlNlZ21lbnRJbmZvAAAAAIezWJt1VPhvEXQ1ddEbjLwAAAAACAAAAAcAAAAAAQAAAAgAAAAHAAAAAAAAAAIBCgJvcwVMaW51eAtqYXZhLnZlbmRvcgxBZG9wdE9wZW5KREsMamF2YS52ZXJzaW9uBjE1LjAuMQ9qYXZhLnZtLnZlcnNpb24IMTUuMC4xKzkObHVjZW5lLnZlcnNpb24FOC43LjAHb3MuYXJjaAdhYXJjaDY0FGphdmEucnVudGltZS52ZXJzaW9uCDE1LjAuMSs5BnNvdXJjZQVmbHVzaApvcy52ZXJzaW9uEDYuMTAuMTQtbGludXhraXQJdGltZXN0YW1wDTE3NDcxNTM3OTg4OTkDBl8xLmNmcwZfMS5jZmUFXzEuc2kBH0x1Y2VuZTg3U3RvcmVkRmllbGRzRm9ybWF0Lm1vZGUKQkVTVF9TUEVFRADAKJPoAAAAAAAAAABTcGwu",
      "offset" : 0,
      "length" : 369,
      "valid" : true
    }
  }, {
    "name" : "__2wEk_e49Sga2tYOtVKKXbg",
    "physicalName" : "_0.cfs",
    "length" : 3201,
    "checksum" : "w612qz",
    "partSize" : 9223372036854775807,
    "numberOfParts" : 1,
    "writtenBy" : "8.7.0",
    "metaHash" : null
  }, {
    "name" : "__YvcTWOjFSeqJy1j7fD658w",
    "physicalName" : "_0_1_Lucene80_0.dvd",
    "length" : 87,
    "checksum" : "1pll3zf",
    "partSize" : 9223372036854775807,
    "numberOfParts" : 1,
    "writtenBy" : "8.7.0",
    "metaHash" : null
  }, {
    "name" : "__5XYCMt1cSeqJ6i1CxWbQFA",
    "physicalName" : "_0_1.fnm",
    "length" : 1026,
    "checksum" : "ousa4c",
    "partSize" : 9223372036854775807,
    "numberOfParts" : 1,
    "writtenBy" : "8.7.0",
    "metaHash" : null
  }, {
    "name" : "__5mxLpJUDQ2GURRMuTWGkWg",
    "physicalName" : "_0_1_Lucene80_0.dvm",
    "length" : 160,
    "checksum" : "a05jue",
    "partSize" : 9223372036854775807,
    "numberOfParts" : 1,
    "writtenBy" : "8.7.0",
    "metaHash" : null
  }, {
    "name" : "v__W5As5o6gSwWEy8AAcO0Uxw",
    "physicalName" : "segments_4",
    "length" : 449,
    "checksum" : "azrz38",
    "partSize" : 9223372036854775807,
    "numberOfParts" : 1,
    "writtenBy" : "8.7.0",
    "metaHash" : {
      "bytes" : "P9dsFwhzZWdtZW50cwAAAAqHs1ibdVT4bxF0NXXRG4zCATQIBwAIAAAAAAAAAA4CAAAAAggHAAJfMIezWJt1VPhvEXQ1ddEbjKoITHVjZW5lODf//////////wAAAAAAAAAAAAAAAQAAAAAAAAABAAAAAQGHs1ibdVT4bxF0NXXRG4zAAQhfMF8xLmZubQAAAAEAAAAJAhNfMF8xX0x1Y2VuZTgwXzAuZHZkE18wXzFfTHVjZW5lODBfMC5kdm0CXzGHs1ibdVT4bxF0NXXRG4y8CEx1Y2VuZTg3//////////8AAAAA/////////////////////wAAAAEBh7NYm3VU+G8RdDV10RuMvgAAAAAABhBsb2NhbF9jaGVja3BvaW50ATMcbWF4X3Vuc2FmZV9hdXRvX2lkX3RpbWVzdGFtcAItMQ10cmFuc2xvZ191dWlkFjBvcVdiV2d3U29TYnZyUmZKUVBLbFETbWluX3JldGFpbmVkX3NlcV9ubwEwDGhpc3RvcnlfdXVpZBZTWGdmV3hnN1NKU2c5YzUzQWc2RzhRCm1heF9zZXFfbm8BM8Aok+gAAAAAAAAAACefVWQ=",
      "offset" : 0,
      "length" : 449,
      "valid" : true
    }
  } ]
}
```

We can break down the files from new and old into the following:

Shared in OLD & NEW
_0.cfe, _0.si, _0.cfs

Only in OLD
segments_3

Only in NEW
_1.cfs, _1.cfe, _1.si, _0_1_Lucene80_0.dvd, _0_1.fnm, _0_1_Lucene80_0.dvm, segments_4
