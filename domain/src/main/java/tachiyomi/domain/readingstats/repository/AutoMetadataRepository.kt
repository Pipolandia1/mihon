package tachiyomi.domain.readingstats.repository

import tachiyomi.domain.readingstats.comics.AutoState

/** Where automatic metadata stands for each work (H13). Not backed up: it is done again after a restore. */
interface AutoMetadataRepository {
    suspend fun all(): List<AutoState>
    suspend fun put(state: AutoState)
}
