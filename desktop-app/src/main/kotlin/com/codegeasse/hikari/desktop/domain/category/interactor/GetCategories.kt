package com.codegeasse.hikari.desktop.domain.category.interactor

import com.codegeasse.hikari.desktop.domain.category.model.Category
import com.codegeasse.hikari.desktop.domain.category.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow

class GetCategories(
    private val repository: CategoryRepository,
) {
    fun subscribe(): Flow<List<Category>> = repository.subscribeCategories()

    suspend fun awaitAll(): List<Category> = repository.getCategories()
}
