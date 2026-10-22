package com.ferbotz.billanta.data.repo

import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.data.local.CustomerValuesLocal
import com.ferbotz.billanta.data.local.ProductValuesLocal
import com.ferbotz.billanta.data.local.SingletonValuesLocal
import com.ferbotz.billanta.fields.FieldValues
import com.ferbotz.billanta.records.BusinessProfile
import com.ferbotz.billanta.records.Customer
import com.ferbotz.billanta.records.InvoiceSettings
import com.ferbotz.billanta.records.Product
import kotlinx.coroutines.flow.Flow

/*
 * The four records the global forms edit (`GET /forms`, FIELDS.md §5). Each screen hands back the
 * whole values map it shows; the local store works out which keys changed, and only those go up.
 */

/** The business profile and the settings. */
class ProfileRepository(
    private val singletons: SingletonValuesLocal,
    private val clock: EpochClock,
    private val onLocalMutation: () -> Unit,
    /** Drafts copy the profile, so they follow every change to it (FIELDS.md §8.4). */
    private val onProfileChanged: suspend () -> Unit,
) {
    fun observeProfile(): Flow<BusinessProfile?> = singletons.observeProfile()

    fun observeSettings(): Flow<InvoiceSettings?> = singletons.observeSettings()

    suspend fun getProfile(): BusinessProfile? = singletons.getProfile()

    suspend fun getSettings(): InvoiceSettings? = singletons.getSettings()

    suspend fun saveProfile(values: FieldValues) {
        if (values == singletons.getProfile()?.values) return
        singletons.saveProfileLocal(values, clock.nowMillis())
        onLocalMutation()
        onProfileChanged()
    }

    suspend fun saveSettings(values: FieldValues) {
        if (values == singletons.getSettings()?.values) return
        singletons.saveSettingsLocal(values, clock.nowMillis())
        onLocalMutation()
    }
}

class CustomerRepository(
    private val customers: CustomerValuesLocal,
    private val clock: EpochClock,
    private val onLocalMutation: () -> Unit,
    /** Drafts billed to a customer copy it, so they follow every change to it. */
    private val onCustomerChanged: suspend () -> Unit,
) {
    fun observeCustomers(query: String = ""): Flow<List<Customer>> = customers.observeList(query)

    fun observeCustomer(id: String): Flow<Customer?> = customers.observeById(id)

    suspend fun getCustomer(id: String): Customer? = customers.getById(id)

    /** Creates or edits a customer. Returns what was stored. */
    suspend fun save(customer: Customer): Customer {
        val existing = customers.getById(customer.id)
        if (existing != null && existing.values == customer.values) return existing
        val now = clock.nowMillis()
        val saved = customers.saveLocal(customer.copy(createdAtMillis = existing?.createdAtMillis ?: now, updatedAtMillis = now))
        onLocalMutation()
        onCustomerChanged()
        return saved
    }

    /** Invoices keep their own copy, so deleting a customer changes no invoice. */
    suspend fun delete(id: String) {
        customers.softDelete(id, clock.nowMillis())
        onLocalMutation()
    }
}

class ProductRepository(
    private val products: ProductValuesLocal,
    private val clock: EpochClock,
    private val onLocalMutation: () -> Unit,
) {
    fun observeProducts(query: String = ""): Flow<List<Product>> = products.observeList(query)

    suspend fun getProduct(id: String): Product? = products.getById(id)

    /** Creates or edits a product. A line never calls this: products change only here. */
    suspend fun save(product: Product): Product {
        val existing = products.getById(product.id)
        if (existing != null && existing.values == product.values) return existing
        val now = clock.nowMillis()
        val saved = products.saveLocal(product.copy(createdAtMillis = existing?.createdAtMillis ?: now, updatedAtMillis = now))
        onLocalMutation()
        return saved
    }

    /** A deleted product keeps its photo in storage, and lines that copied it keep theirs. */
    suspend fun delete(id: String) {
        products.softDelete(id, clock.nowMillis())
        onLocalMutation()
    }
}
