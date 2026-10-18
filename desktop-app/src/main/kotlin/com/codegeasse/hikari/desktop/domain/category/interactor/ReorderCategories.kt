package com.codegeasse.hikari.desktop.domain.category.interactor

import com.codegeasse.hikari.desktop.domain.category.model.Category
import com.codegeasse.hikari.desktop.domain.category.repository.CategoryRepository

class ReorderCategories(
    private val repository: CategoryRepository,
) {
    suspend fun await(categories: List<Category>): Boolean {
        if (categories.isEmpty()) return false
        val indexed = categories.mapIndexed { idx, cat -> cat.copy(order = idx) }
        return repository.reorderCategories(indexed)
    }
}
