// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/vespalib/net/http/state_explorer.h>

namespace vespalib {
class ISequencedTaskExecutor;
}

namespace proton {

/**
 * Class used to explore a SequencedTaskExecutor.
 */
class SequencedTaskExecutorExplorer : public vespalib::StateExplorer {
private:
    // This is non-const in order to call get_raw_stats().
    vespalib::ISequencedTaskExecutor* _executor;

public:
    SequencedTaskExecutorExplorer(vespalib::ISequencedTaskExecutor* executor);

    void get_state(const vespalib::slime::Inserter& inserter, bool full) const override;
};

}

