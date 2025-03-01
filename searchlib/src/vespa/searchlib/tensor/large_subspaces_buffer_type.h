// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/vespalib/datastore/array_store_config.h>
#include <vespa/vespalib/datastore/buffer_type.h>
#include <vespa/vespalib/util/array.h>
#include <memory>

namespace vespalib::alloc { class MemoryAllocator; }

namespace search::tensor {

class TensorBufferOperations;
class TensorBufferTypeMapper;

/*
 * Class representing buffer type for tensors with a large number of
 * subspaces in array store. Tensor buffers are externally allocated
 * (cf. vespalib::Array).
 */
class LargeSubspacesBufferType : public vespalib::datastore::BufferType<vespalib::Array<char>>
{
    using AllocSpec = vespalib::datastore::ArrayStoreConfig::AllocSpec;
    using ArrayType = vespalib::Array<char>;
    using ParentType = vespalib::datastore::BufferType<ArrayType>;
    using CleanContext = typename ParentType::CleanContext;
    std::shared_ptr<vespalib::alloc::MemoryAllocator> _memory_allocator;
    TensorBufferOperations& _ops;
public:
    LargeSubspacesBufferType(const AllocSpec& spec, std::shared_ptr<vespalib::alloc::MemoryAllocator> memory_allocator, TensorBufferTypeMapper& type_mapper) noexcept;
    ~LargeSubspacesBufferType() override;
    void clean_hold(void* buffer, size_t offset, EntryCount num_entries, CleanContext cleanCtx) override;
    void destroy_entries(void *buffer, EntryCount num_entries) override;
    void fallback_copy(void *newBuffer, const void *oldBuffer, EntryCount num_entries) override;
    void initialize_reserved_entries(void *buffer, EntryCount reserved_entries) override;
    const vespalib::alloc::MemoryAllocator* get_memory_allocator() const override;
    vespalib::alloc::Alloc initial_alloc() const noexcept {
        return _memory_allocator ? vespalib::alloc::Alloc::alloc_with_allocator(_memory_allocator.get()) : vespalib::alloc::Alloc::alloc(0, vespalib::alloc::MemoryAllocator::HUGEPAGE_SIZE);
    }
};

}
