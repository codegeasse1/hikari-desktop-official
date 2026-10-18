package com.codegeasse.hikari.desktop.domain.category.interactor

import com.codegeasse.hikari.desktop.domain.category.repository.CategoryRepository

class SetItemCategory(
    private val repository: CategoryRepository,
) {
    suspend fun await(bookmarkId: String, categoryId: Int): Boolean {
        if (bookmarkId.isBlank()) return false
        return repository.setItemCategory(bookmarkId, categoryId)
    }
}
