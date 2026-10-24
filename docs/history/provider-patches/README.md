# Historical provider patches (R1 to R3)

These are the source deltas that the R1, R2 and R3 releases of this mod needed in MCA: Quests, MCA: Conversations
and MCA: Crime, exported with their baseline hashes while those changes were still private. They moved here from
`pack/r1/providers`, `pack/r2/providers` and `pack/r3/providers` in 0.1.1.

They are a record, not something to apply:

- Every change in them now ships natively: the quest, commission and atlas halves in MCA: Quests 1.7.0, the
  conversation half in MCA: Conversations 1.8.0, and the jurisdiction half in MCA: Crime 0.7.5.
- They predate this mod's 0.1.1 rename from Ultima Kingdoms, so they still name `ultima_kingdoms` and
  `com.ultimakingdoms`. Current add-on releases bind `mcakingdoms` (and, for one release, the old id).
- Their hash manifests match only the old baselines, so applying them to a current checkout fails or corrupts it.

The staging scripts under `pack/r1` to `pack/r3` and `tools/test/verify_r2_pack.py` still read them from here, so a
historical delivery can be rebuilt exactly as it was validated.
