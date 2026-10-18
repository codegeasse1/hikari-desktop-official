package com.codegeasse.hikari.desktop.domain.category.interactor

import com.codegeasse.hikari.desktop.domain.category.repository.CategoryRepository

class DeleteCategory(
    private val repository: CategoryRepository,
) {
    suspend fun await(id: Int): Boolean {
        return repository.deleteCategory(id)
    }
}
