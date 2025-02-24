// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/metrics/jsonwriter.h>
#include <vespa/metrics/metrics.h>
#include <vespa/metrics/metricmanager.h>
#include <vespa/metrics/state_api_adapter.h>
#include <vespa/metrics/textwriter.h>
#include <vespa/vespalib/data/slime/slime.h>
#include <vespa/vespalib/gtest/gtest.h>
#include <vespa/vespalib/stllike/asciistream.h>
#include <vespa/vespalib/util/size_literals.h>
#include <vespa/vespalib/util/time.h>
#include <vespa/vespalib/data/simple_buffer.h>
#include <vespa/vespalib/util/atomic.h>
#include <mutex>
#include <thread>

#include <vespa/log/log.h>
LOG_SETUP(".test.metricmanager");

using namespace vespalib::atomic;
using config::ConfigUri;

namespace metrics {

struct MetricManagerTest : public ::testing::Test {

    // Ugly indirection hack caused by some tests using friended internals of
    // MetricManager that aren't accessible to "freestanding" fixtures. So we
    // get the test to do the necessary poking and prodding for us instead.
    void takeSnapshots(MetricManager& mm, time_t timeToProcess) {
        mm.takeSnapshots(mm.getMetricLock(), system_time(vespalib::from_s<system_time::duration>(timeToProcess)));
    }
};

namespace {

struct SubMetricSet : public MetricSet
{
    DoubleValueMetric val1;
    DoubleValueMetric val2;
    SumMetric<DoubleValueMetric> valsum;

    SubMetricSet(const Metric::String & name, MetricSet* owner);
    ~SubMetricSet();
};


SubMetricSet::SubMetricSet(const Metric::String & name, MetricSet* owner)
    : MetricSet(name, {{"sub"}}, "sub desc", owner),
      val1("val1", {{"tag4"},{"snaptest"}}, "val1 desc", this),
      val2("val2", {{"tag5"}}, "val2 desc", this),
      valsum("valsum", {{"tag4"},{"snaptest"}}, "valsum desc", this)
{
    valsum.addMetricToSum(val1);
    valsum.addMetricToSum(val2);
}
SubMetricSet::~SubMetricSet() = default;

struct MultiSubMetricSet
{
    MetricSet set;
    LongCountMetric count;
    SubMetricSet a;
    SubMetricSet b;
    SumMetric<MetricSet> sum;

    MultiSubMetricSet(MetricSet* owner);
    ~MultiSubMetricSet();
};

MultiSubMetricSet::MultiSubMetricSet(MetricSet* owner)
    : set("multisub", {{"multisub"}}, "", owner),
      count("count", {{"snaptest"}}, "counter", &set),
      a("a", &set),
      b("b", &set),
      sum("sum", {{"snaptest"}}, "", &set)
{
    sum.addMetricToSum(a);
    sum.addMetricToSum(b);
}

MultiSubMetricSet::~MultiSubMetricSet() = default;

struct TestMetricSet {
    MetricSet set;
    DoubleValueMetric val1;
    DoubleValueMetric val2;
    DoubleValueMetric val3;
    DoubleValueMetric val4;
    DoubleValueMetric val5;
    DoubleValueMetric val6;
    DoubleValueMetric val7;
    DoubleValueMetric val8;
    SubMetricSet val9;
    MultiSubMetricSet val10;

    TestMetricSet();
    ~TestMetricSet();
};

TestMetricSet::TestMetricSet()
    : set("temp", {{"test"}}, "desc of test set"),
      val1("val1", {{"tag1"}}, "val1 desc", &set),
      val2("val2", {{"tag1"},{"tag2"}}, "val2 desc", &set),
      val3("val3", {{"tag2"},{"tag3"}}, "val3 desc", &set),
      val4("val4", {{"tag3"}}, "val4 desc", &set),
      val5("val5", {{"tag2"}}, "val5 desc", &set),
      val6("val6", {{"tag4"},{"snaptest"}}, "val6 desc", &set),
      val7("val7", {}, "val7 desc", &set),
      val8("val8", {{"tag6"}}, "val8 desc", &set),
      val9("sub", &set),
      val10(&set)
{ }

TestMetricSet::~TestMetricSet() = default;


struct MetricNameVisitor : public MetricVisitor {
    std::ostringstream ost;
    bool debug;

    MetricNameVisitor(bool debug_ = false) : debug(debug_) {}

    bool visitMetricSet(const MetricSet& metricSet, bool autoGenerated) override {
        if (debug) {
            ost << "<" << (autoGenerated ? "*" : "")
                << metricSet.getPath() << ">\n";
        }
        return true;
    }
    void doneVisitingMetricSet(const MetricSet& metricSet) override {
        if (debug) {
            ost << "</" << metricSet.getPath() << ">\n";
        }
    }

    bool visitMetric(const Metric& m, bool autoGenerated) override {
        ost << (autoGenerated ? "*" : "") << m.getPath() << "\n";
        return true;
    }

    std::string toString() const { return ost.str(); }
};

}

namespace {

std::pair<std::string, std::string>
getMatchedMetrics(const vespalib::string& config)
{
    TestMetricSet mySet;
    MetricManager mm;
    mm.registerMetric(mm.getMetricLock(), mySet.set);
    mm.init(ConfigUri(config));
    MetricNameVisitor visitor;

    /** Take a copy to verify clone works.
    std::list<Metric::SP> ownerList;
    MetricSet::UP copy(dynamic_cast<MetricSet*>(
                mm.getMetrics().clone(ownerList)));
    mm.visit(*copy, visitor, "consumer");
    */

    MetricLockGuard g(mm.getMetricLock());
    mm.visit(g, mm.getActiveMetrics(g), visitor, "consumer");
    const MetricManager::ConsumerSpec * consumerSpec = mm.getConsumerSpec(g, "consumer");
    return { visitor.toString(), consumerSpec ? consumerSpec->toString() : "Non-existing consumer" };
}

}

#define ASSERT_CONSUMER_MATCH(name, expected, config) \
{ \
    std::pair<std::string, std::string> consumerMatch(getMatchedMetrics(config)); \
    EXPECT_EQ("\n" + expected, "\n" + consumerMatch.first) << (name + std::string(": ") + consumerMatch.second); \
}

TEST_F(MetricManagerTest, test_consumer_visitor)
{
    // Add one tag and a name, check that we get all three.
    ASSERT_CONSUMER_MATCH("testAddTagAndName", std::string(
            "temp.val1\n"
            "temp.val2\n"
            "temp.val4\n"
            "*temp.multisub.sum.val1\n"
        ),
            "raw:"
            "consumer[1]\n"
            "consumer[0].name consumer\n"
            "consumer[0].tags[1]\n"
            "consumer[0].tags[0] tag1\n"
            "consumer[0].addedmetrics[2]\n"
            "consumer[0].addedmetrics[0] temp.val4\n"
            "consumer[0].addedmetrics[1] temp.multisub.sum.val1\n"
    );
    // Add two tags, remove one
    ASSERT_CONSUMER_MATCH("testAddAndRemoveTag", std::string(
            "temp.val1\n"
            "temp.val4\n"
        ),
            "raw:"
            "consumer[1]\n"
            "consumer[0].name consumer\n"
            "consumer[0].tags[2]\n"
            "consumer[0].tags[0] tag1\n"
            "consumer[0].tags[1] tag3\n"
            "consumer[0].removedtags[1]\n"
            "consumer[0].removedtags[0] tag2\n"
    );
    // Test simple wildcards
    ASSERT_CONSUMER_MATCH("testWildCards", std::string(
            "temp.val1\n"
            "temp.val2\n"
            "temp.val3\n"
            "temp.val4\n"
            "temp.val5\n"
            "temp.val6\n"
            "temp.val7\n"
            "temp.val8\n"
        ),
            "raw:"
            "consumer[1]\n"
            "consumer[0].name consumer\n"
            "consumer[0].addedmetrics[1]\n"
            "consumer[0].addedmetrics[0] temp.*\n"
            "consumer[0].removedmetrics[2]\n"
            "consumer[0].removedmetrics[0] temp.sub.*\n"
            "consumer[0].removedmetrics[1] temp.multisub.*\n"
    );
    // Test more wildcards
    ASSERT_CONSUMER_MATCH("testWildCards2", std::string(
            "temp.sub.val1\n"
        ),
            "raw:"
            "consumer[1]\n"
            "consumer[0].name consumer\n"
            "consumer[0].addedmetrics[1]\n"
            "consumer[0].addedmetrics[0] temp.*.val1\n"
    );
    // test adding all
    ASSERT_CONSUMER_MATCH("testAddAll", std::string(
            "metricmanager.periodichooklatency\n"
            "metricmanager.snapshothooklatency\n"
            "metricmanager.resetlatency\n"
            "metricmanager.snapshotlatency\n"
            "metricmanager.sleeptime\n"
            "temp.val1\n"
            "temp.val2\n"
            "temp.val3\n"
            "temp.val4\n"
            "temp.val5\n"
            "temp.val6\n"
            "temp.val7\n"
            "temp.val8\n"
            "temp.sub.val1\n"
            "temp.sub.val2\n"
            "*temp.sub.valsum\n"
            "temp.multisub.count\n"
            "temp.multisub.a.val1\n"
            "temp.multisub.a.val2\n"
            "*temp.multisub.a.valsum\n"
            "temp.multisub.b.val1\n"
            "temp.multisub.b.val2\n"
            "*temp.multisub.b.valsum\n"
            "*temp.multisub.sum.val1\n"
            "*temp.multisub.sum.val2\n"
            "*temp.multisub.sum.valsum\n"
        ),
            "raw:"
            "consumer[1]\n"
            "consumer[0].name consumer\n"
            "consumer[0].addedmetrics[1]\n"
            "consumer[0].addedmetrics[0] *\n"
    );
    // test adding all using tags
    ASSERT_CONSUMER_MATCH("testAddAll2", std::string(
            "temp.val1\n"
            "temp.val2\n"
            "temp.val3\n"
            "temp.val4\n"
            "temp.val5\n"
            "temp.val6\n"
            "temp.val7\n"
            "temp.val8\n"
            "temp.sub.val1\n"
            "temp.sub.val2\n"
            "*temp.sub.valsum\n"
            "temp.multisub.count\n"
            "temp.multisub.a.val1\n"
            "temp.multisub.a.val2\n"
            "*temp.multisub.a.valsum\n"
            "temp.multisub.b.val1\n"
            "temp.multisub.b.val2\n"
            "*temp.multisub.b.valsum\n"
            "*temp.multisub.sum.val1\n"
            "*temp.multisub.sum.val2\n"
            "*temp.multisub.sum.valsum\n"
        ),
            "raw:"
            "consumer[1]\n"
            "consumer[0].name consumer\n"
            "consumer[0].tags[1]\n"
            "consumer[0].tags[0] *\n"
    );
    // Test that all metrics are added when a metricsset is added by name
    ASSERT_CONSUMER_MATCH("testSpecifiedSetName", std::string(
            "temp.sub.val1\n"
            "temp.sub.val2\n"
            "*temp.sub.valsum\n"
        ),
            "raw:"
            "consumer[1]\n"
            "consumer[0].name consumer\n"
            "consumer[0].addedmetrics[1]\n"
            "consumer[0].addedmetrics[0] temp.sub\n"
    );
    // Test that all metrics are added when a metricsset is added by tag
    ASSERT_CONSUMER_MATCH("testSpecifiedSetTag", std::string(
            "temp.sub.val1\n"
            "temp.sub.val2\n"
            "*temp.sub.valsum\n"
            "temp.multisub.a.val1\n"
            "temp.multisub.a.val2\n"
            "*temp.multisub.a.valsum\n"
            "temp.multisub.b.val1\n"
            "temp.multisub.b.val2\n"
            "*temp.multisub.b.valsum\n"
        ),
            "raw:"
            "consumer[1]\n"
            "consumer[0].name consumer\n"
            "consumer[0].tags[1]\n"
            "consumer[0].tags[0] sub\n"
    );
    // Test that all metrics are added from the set except those with a
    // certain tag.
    ASSERT_CONSUMER_MATCH("testSpecifiedSetTagWithExceptionTags", std::string(
            "temp.val1\n"
            "temp.val4\n"
            "temp.val7\n"
            "temp.val8\n"
            "temp.sub.val2\n"
            "temp.multisub.count\n"
            "temp.multisub.a.val2\n"
            "temp.multisub.b.val2\n"
            "*temp.multisub.sum.val2\n"
        ),
            "raw:"
            "consumer[1]\n"
            "consumer[0].name consumer\n"
            "consumer[0].tags[1]\n"
            "consumer[0].tags[0] test\n"
            "consumer[0].removedtags[2]\n"
            "consumer[0].removedtags[0] tag2\n"
            "consumer[0].removedtags[1] tag4\n"
    );
};

namespace {

class FakeTimer : public MetricManager::Timer {
    std::atomic<time_t> _time;
public:
    FakeTimer(time_t startTime = 0) : _time(startTime) {}
    time_point getTime() const override { return time_point(vespalib::from_s<time_point::duration>(load_relaxed(_time))); }
    void set_time(time_t t) noexcept { store_relaxed(_time, t); }
    // Not safe for multiple writers, only expected to be called by test.
    void add_time(time_t t) noexcept { set_time(load_relaxed(_time) + t); }
};

struct BriefValuePrinter : public MetricVisitor {
    uint32_t count;
    std::ostringstream ost;

    BriefValuePrinter() : count(0), ost() {}

    bool visitMetric(const Metric& metric, bool) override {
        if (++count > 1) ost << ",";
        //ost << metric.getPath() << ":";
        ost << metric.getDoubleValue("value");
        return true;
    }
};

bool waitForTimeProcessed(const MetricManager& mm, time_point::duration processtime, uint32_t timeout = 120)
{
    uint32_t lastchance = time(0) + timeout;
    while (time(0) < lastchance) {
        if (mm.getLastProcessedTime() >= time_point(processtime)) return true;
        mm.timeChangedNotification();
        std::this_thread::sleep_for(10ms);
    }
    return false;
}

std::string dumpAllSnapshots(const MetricManager& mm, const std::string& consumer)
{
    std::ostringstream ost;
    ost << "\n";
    {
        MetricLockGuard metricLock(mm.getMetricLock());
        BriefValuePrinter briefValuePrinter;
        mm.visit(metricLock, mm.getActiveMetrics(metricLock), briefValuePrinter, consumer);
        ost << "Current: " << briefValuePrinter.ost.str() << "\n";
    }
    {
        MetricLockGuard metricLock(mm.getMetricLock());
        BriefValuePrinter briefValuePrinter;
        mm.visit(metricLock, mm.getTotalMetricSnapshot(metricLock), briefValuePrinter, consumer);
        ost << "Total: " << briefValuePrinter.ost.str() << "\n";
    }

    MetricLockGuard metricLock(mm.getMetricLock());
    auto periods = mm.getSnapshotPeriods(metricLock);
    for (vespalib::duration period : periods) {
        const MetricSnapshotSet& set(mm.getMetricSnapshotSet(metricLock, period));
        ost << set.getName() << "\n";
        for (uint32_t count=0,j=0; j<2; ++j) {
            if (set.getCount() == 1 && j == 1) continue;
            const MetricSnapshot& snap(set.getSnapshot(j == 1));
            BriefValuePrinter briefValuePrinter;
            mm.visit(metricLock, snap, briefValuePrinter, consumer);
            ost << "  " << count++ << " " << &snap.getMetrics() << ": "
                << briefValuePrinter.ost.str() << "\n";
        }
    }
    return ost.str();
}

}

#define ASSERT_VALUES(mm, period, expected) \
{ \
    MetricLockGuard lockGuard(mm.getMetricLock()); \
    BriefValuePrinter briefValuePrinter; \
    if (period < vespalib::duration::zero()) { \
        mm.visit(lockGuard, mm.getActiveMetrics(lockGuard), briefValuePrinter, "snapper"); \
    } else if (period == vespalib::duration::zero()) { \
        mm.visit(lockGuard, mm.getTotalMetricSnapshot(lockGuard), briefValuePrinter, "snapper"); \
    } else { \
        mm.visit(lockGuard, mm.getMetricSnapshot(lockGuard, period), briefValuePrinter, "snapper"); \
    } \
    EXPECT_EQ(std::string(expected), briefValuePrinter.ost.str()) << dumpAllSnapshots(mm, "snapper"); \
}

#define ASSERT_PROCESS_TIME(mm, time) \
{ \
    LOG(info, "Waiting for processed time %s.", vespalib::to_string(time_point(time)).c_str()); \
    bool gotToCorrectProgress = waitForTimeProcessed(mm, (time)); \
    if (!gotToCorrectProgress) \
        FAIL() << "Failed to get to processed time within timeout"; \
}

TEST_F(MetricManagerTest, test_snapshots)
{
    auto timerImpl = std::make_unique<FakeTimer>(1000);
    FakeTimer & timer = *timerImpl;
    TestMetricSet mySet;
    MetricManager mm(std::move(timerImpl));
    {
        MetricLockGuard lockGuard(mm.getMetricLock());
        mm.registerMetric(lockGuard, mySet.set);
        EXPECT_FALSE(mm.any_snapshots_taken(lockGuard)); // well-defined prior to init()
    }
    mm.init(ConfigUri("raw:"
                      "consumer[2]\n"
                      "consumer[0].name snapper\n"
                      "consumer[0].tags[1]\n"
                      "consumer[0].tags[0] snaptest\n"
                      "consumer[1].name log\n"
                      "consumer[1].tags[1]\n"
                      "consumer[1].tags[0] snaptest\n"));
    MetricNameVisitor visitor;
    {
        MetricLockGuard lockGuard(mm.getMetricLock());
        EXPECT_FALSE(mm.any_snapshots_taken(lockGuard)); // No snapshots yet
        mm.visit(lockGuard, mm.getActiveMetrics(lockGuard), visitor, "snapper");
        const MetricManager::ConsumerSpec * consumerSpec = mm.getConsumerSpec(lockGuard, "snapper");
        EXPECT_EQ(std::string("\n"
                              "temp.val6\n"
                              "temp.sub.val1\n"
                              "*temp.sub.valsum\n"
                              "temp.multisub.count\n"
                              "temp.multisub.a.val1\n"
                              "*temp.multisub.a.valsum\n"
                              "temp.multisub.b.val1\n"
                              "*temp.multisub.b.valsum\n"
                              "*temp.multisub.sum.val1\n"
                              "*temp.multisub.sum.val2\n"
                              "*temp.multisub.sum.valsum\n"),
                  "\n" + visitor.toString()) << (consumerSpec ? consumerSpec->toString() : "Non-existing consumer");
    }
    // Initially, there should be no metrics logged
    ASSERT_PROCESS_TIME(mm, 1000s);
    ASSERT_VALUES(mm, 5 * 60s, "");

    // Adding metrics done in first five minutes.
    mySet.val6.addValue(2);
    mySet.val9.val1.addValue(4);
    mySet.val10.count.inc();
    mySet.val10.a.val1.addValue(7);
    mySet.val10.a.val2.addValue(2);
    mySet.val10.b.val1.addValue(1);
    timer.add_time(5 * 60);
    ASSERT_PROCESS_TIME(mm, 1000s + 5 * 60s);
    ASSERT_VALUES(mm,  5 * 60s, "2,4,4,1,7,9,1,1,8,2,10");
    ASSERT_VALUES(mm, 60 * 60s, "");
    ASSERT_VALUES(mm,  0 * 60s, "2,4,4,1,7,9,1,1,8,2,10");
    {
        auto guard = mm.getMetricLock();
        EXPECT_TRUE(mm.any_snapshots_taken(guard)); // At least one snapshot has been taken
    }

    // Adding metrics done in second five minute period. Total should
    // be updated to account for both
    mySet.val6.addValue(4);
    mySet.val9.val1.addValue(5);
    mySet.val10.count.inc();
    mySet.val10.a.val1.addValue(8);
    mySet.val10.a.val2.addValue(3);
    mySet.val10.b.val1.addValue(2);
    timer.add_time(5 * 60);
    ASSERT_PROCESS_TIME(mm, 1000s + 5 * 60 * 2s);
    ASSERT_VALUES(mm,  5 * 60s, "4,5,5,1,8,11,2,2,10,3,13");
    ASSERT_VALUES(mm, 60 * 60s, "");
    ASSERT_VALUES(mm,  0 * 60s, "4,5,5,2,8,11,2,2,10,3,13");

    // Adding another five minute period where nothing have happened.
    // Metric for last 5 minutes should be 0.
    timer.add_time(5 * 60);
    ASSERT_PROCESS_TIME(mm, 1000s + 5 * 60s * 3);
    ASSERT_VALUES(mm,  5 * 60s, "0,0,0,0,0,0,0,0,0,0,0");
    ASSERT_VALUES(mm, 60 * 60s, "");
    ASSERT_VALUES(mm,  0 * 60s, "4,5,5,2,8,11,2,2,10,3,13");

    // Advancing time to 60 minute period, we should create a proper
    // 60 minute period timer.
    mySet.val6.addValue(6);
    for (uint32_t i=0; i<9; ++i) { // 9 x 5 minutes. Avoid snapshot bumping
                                   // due to taking snapshots in the past
        timer.add_time(5 * 60);
        ASSERT_PROCESS_TIME(mm, 1000s + 5 * 60s * (4 + i));
    }
    ASSERT_VALUES(mm,  5 * 60s, "0,0,0,0,0,0,0,0,0,0,0");
    ASSERT_VALUES(mm, 60 * 60s, "6,5,5,2,8,11,2,2,10,3,13");
    ASSERT_VALUES(mm,  0 * 60s, "6,5,5,2,8,11,2,2,10,3,13");

    // Test that reset works
    mm.reset(system_time(1000s));
    ASSERT_VALUES(mm,      -1s, "0,0,0,0,0,0,0,0,0,0,0");
    ASSERT_VALUES(mm,  5 * 60s, "0,0,0,0,0,0,0,0,0,0,0");
    ASSERT_VALUES(mm, 60 * 60s, "0,0,0,0,0,0,0,0,0,0,0");
    ASSERT_VALUES(mm,  0 * 60s, "0,0,0,0,0,0,0,0,0,0,0");
}

TEST_F(MetricManagerTest, test_json_output)
{
    auto timerImpl = std::make_unique<FakeTimer>(1000);
    FakeTimer & timer = *timerImpl;
    MetricManager mm(std::move(timerImpl));
    TestMetricSet mySet;
    {
        MetricLockGuard lockGuard(mm.getMetricLock());
        mm.registerMetric(lockGuard, mySet.set);
    }

    // Initialize metric manager to get snapshots created.
    mm.init(ConfigUri("raw:"
                      "consumer[1]\n"
                      "consumer[0].name snapper\n"
                      "consumer[0].tags[1]\n"
                      "consumer[0].tags[0] snaptest\n"));

    {
        // No snapshots have been taken yet, so the non-total getMetrics call should return
        // the empty string (i.e. no metrics produced).
        metrics::StateApiAdapter adapter(mm);
        auto json_str = adapter.getMetrics("snapper");
        EXPECT_EQ(json_str, "");
    }

    takeSnapshots(mm, 1000);

    // Adding metrics to have some values in them
    mySet.val6.addValue(2);
    mySet.val9.val1.addValue(4);
    mySet.val10.count.inc();
    mySet.val10.a.val1.addValue(7);
    mySet.val10.a.val2.addValue(2);
    mySet.val10.b.val1.addValue(1);

    timer.set_time(1300);
    takeSnapshots(mm, 1300);

    // Create json output
    vespalib::asciistream as;
    vespalib::JsonStream jsonStream(as);
    JsonWriter writer(jsonStream);
    {
        MetricLockGuard lockGuard(mm.getMetricLock());
        mm.visit(lockGuard, mm.getMetricSnapshot(lockGuard, 300s, false), writer, "snapper");
    }
    jsonStream.finalize();
    std::string jsonData = as.str();
    // Parse it back
    using namespace vespalib::slime;
    vespalib::Slime slime;
    size_t parsed = JsonFormat::decode(vespalib::Memory(jsonData), slime);
    if (parsed == 0) {
        vespalib::SimpleBuffer buffer;
        JsonFormat::encode(slime, buffer, false);
        std::ostringstream ost;
        ost << "Failed to parse JSON: '\n"
            << jsonData << "'\n:" << buffer.get().make_string() << "\n";
        EXPECT_EQ(jsonData.size(), parsed) << ost.str();
    }
    // Verify some content
    EXPECT_EQ(1000.0, slime.get()["snapshot"]["from"].asDouble()) << jsonData;
    EXPECT_EQ(1300.0, slime.get()["snapshot"]["to"].asDouble()) << jsonData;
    EXPECT_EQ(vespalib::string("temp.val6"),
              slime.get()["values"][0]["name"].asString().make_string()) << jsonData;
    EXPECT_EQ(vespalib::string("val6 desc"),
              slime.get()["values"][0]["description"].asString().make_string()) << jsonData;
    EXPECT_EQ(2.0, slime.get()["values"][0]["values"]["average"].asDouble()) << jsonData;
    EXPECT_EQ(1.0, slime.get()["values"][0]["values"]["count"].asDouble()) << jsonData;
    EXPECT_EQ(2.0, slime.get()["values"][0]["values"]["min"].asDouble()) << jsonData;
    EXPECT_EQ(2.0, slime.get()["values"][0]["values"]["max"].asDouble()) << jsonData;
    EXPECT_EQ(2.0, slime.get()["values"][0]["values"]["last"].asDouble()) << jsonData;

    EXPECT_EQ(vespalib::string("temp.multisub.sum.valsum"),
              slime.get()["values"][10]["name"].asString().make_string()) << jsonData;
    EXPECT_EQ(vespalib::string("valsum desc"),
              slime.get()["values"][10]["description"].asString().make_string()) << jsonData;
    EXPECT_EQ(10.0, slime.get()["values"][10]["values"]["average"].asDouble()) << jsonData;
    EXPECT_EQ(3.0, slime.get()["values"][10]["values"]["count"].asDouble()) << jsonData;
    EXPECT_EQ(1.0, slime.get()["values"][10]["values"]["min"].asDouble()) << jsonData;
    EXPECT_EQ(7.0, slime.get()["values"][10]["values"]["max"].asDouble()) << jsonData;
    EXPECT_EQ(10.0, slime.get()["values"][10]["values"]["last"].asDouble()) << jsonData;

    metrics::StateApiAdapter adapter(mm);
    vespalib::string normal = adapter.getMetrics("snapper");
    EXPECT_EQ(vespalib::string(jsonData), normal);
    vespalib::string total = adapter.getTotalMetrics("snapper");
    EXPECT_GT(total.size(), 0);
    EXPECT_NE(total, normal);
}

namespace {

struct MetricSnapshotTestFixture
{
    MetricManagerTest& test;
    FakeTimer* timer;
    MetricManager manager;
    MetricSet& mset;

    MetricSnapshotTestFixture(MetricManagerTest& callerTest, MetricSet& metricSet)
        : test(callerTest),
          timer(new FakeTimer(1000)),
          manager(std::unique_ptr<MetricManager::Timer>(timer)),
          mset(metricSet)
    {
        {
            MetricLockGuard lockGuard(manager.getMetricLock());
            manager.registerMetric(lockGuard, mset);
        }

        // Initialize metric manager to get snapshots created.
        manager.init(ConfigUri("raw:"
                               "consumer[1]\n"
                               "consumer[0].name snapper\n"
                               "consumer[0].addedmetrics[1]\n"
                               "consumer[0].addedmetrics[0] *\n"));

        test.takeSnapshots(manager, 1000);
    }

    // Take snapshot of metric values from time 1000 to time 1300
    void takeSnapshotsOnce() {
        timer->set_time(1300);
        test.takeSnapshots(manager, 1300);
    }

    std::string renderLastSnapshotAsJson() const {
        vespalib::asciistream as;
        vespalib::JsonStream jsonStream(as, true);
        JsonWriter writer(jsonStream);
        {
            MetricLockGuard lockGuard(manager.getMetricLock());
            manager.visit(lockGuard, manager.getMetricSnapshot(lockGuard, 300s, false), writer, "snapper");
        }
        jsonStream.finalize();
        return as.str();
    }

    std::string renderLastSnapshotAsText(const std::string& matchPattern = ".*") const
    {
        std::ostringstream ss;
        TextWriter writer(ss, 300s, matchPattern, true);
        {
            MetricLockGuard lockGuard(manager.getMetricLock());
            manager.visit(lockGuard, manager.getMetricSnapshot(lockGuard, 300s, false), writer, "snapper");
        }
        return ss.str();
    }
};

class JsonMetricWrapper
{
    std::string _jsonText;
    vespalib::Slime _tree;
public:
    JsonMetricWrapper(const std::string& jsonText);
    ~JsonMetricWrapper();

    // XXX ideally all these should be const, but is not clear how/if it's
    // possible to get a const cursor into the underlying tree.
    vespalib::slime::Cursor& root() { return _tree.get(); }

    vespalib::slime::Cursor& nthMetric(size_t metricIndex) {
        return root()["values"][metricIndex];
    }

    size_t nthMetricDimensionCount(size_t metricIndex) {
        return nthMetric(metricIndex)["dimensions"].children();
    }

    std::string nthMetricName(size_t metricIndex) {
       return nthMetric(metricIndex)["name"].asString().make_string();
    }

    std::string nthMetricDimension(size_t metricIndex, const std::string& key) {
        return nthMetric(metricIndex)["dimensions"][key].asString().make_string();
    }

    // Verify that the nth metric has the given name and the given set of 
    // dimension key-values. Cannot use name alone to check, as multiple metrics
    // may have same name but different dimensions and output ordering of
    // metrics is well defined as being that of the insertion order.
    void verifyDimensions(size_t metricIndex,
                          const std::string& name,
                          const Metric::Tags& dimensions) {
        EXPECT_EQ(name, nthMetricName(metricIndex)) << _jsonText;
        EXPECT_EQ(dimensions.size(), nthMetricDimensionCount(metricIndex)) << _jsonText;
        for (auto& dim : dimensions) {
            EXPECT_EQ(std::string(dim.value()), nthMetricDimension(metricIndex, dim.key())) << _jsonText;
        }
    }
};

JsonMetricWrapper::JsonMetricWrapper(const std::string& jsonText)
    : _jsonText(jsonText)
{
    vespalib::slime::JsonFormat::decode(vespalib::Memory(jsonText), _tree);
}
JsonMetricWrapper::~JsonMetricWrapper() = default;

struct DimensionTestMetricSet : MetricSet
{
    DoubleValueMetric val1;
    LongCountMetric val2;

    DimensionTestMetricSet(MetricSet* owner = nullptr);
    ~DimensionTestMetricSet() override;
};

DimensionTestMetricSet::DimensionTestMetricSet(MetricSet* owner)
    : MetricSet("temp", {{"foo", "megafoo"}, {"bar", "hyperbar"}}, "", owner),
      val1("val1", {{"tag1"}}, "val1 desc", this),
      val2("val2", {{"baz", "superbaz"}}, "val2 desc", this)
{ }
DimensionTestMetricSet::~DimensionTestMetricSet() = default;

}

TEST_F(MetricManagerTest, json_output_supports_multiple_dimensions)
{
    DimensionTestMetricSet mset;
    MetricSnapshotTestFixture fixture(*this, mset);

    mset.val1.addValue(2);
    mset.val2.inc();

    fixture.takeSnapshotsOnce();
    std::string actual = fixture.renderLastSnapshotAsJson();
    JsonMetricWrapper json(actual);

    json.verifyDimensions(0, "temp.val1",
                          {{"foo", "megafoo"}, {"bar", "hyperbar"}});
    json.verifyDimensions(1, "temp.val2",
                          {{"foo", "megafoo"}, {"bar", "hyperbar"},
                           {"baz", "superbaz"}});
}

namespace {

struct NestedDimensionTestMetricSet : MetricSet
{
    DimensionTestMetricSet nestedSet;

    NestedDimensionTestMetricSet();
    ~NestedDimensionTestMetricSet();
};

NestedDimensionTestMetricSet::NestedDimensionTestMetricSet()
    : MetricSet("outer", {{"fancy", "stuff"}}, ""),
      nestedSet(this)
{
}
NestedDimensionTestMetricSet::~NestedDimensionTestMetricSet() = default;

}

TEST_F(MetricManagerTest, json_output_can_nest_dimensions_from_multiple_metric_sets)
{
    NestedDimensionTestMetricSet mset;
    MetricSnapshotTestFixture fixture(*this, mset);

    mset.nestedSet.val1.addValue(2);
    mset.nestedSet.val2.inc();

    fixture.takeSnapshotsOnce();
    std::string actual = fixture.renderLastSnapshotAsJson();
    JsonMetricWrapper json(actual);

    json.verifyDimensions(0, "outer.temp.val1",
                          {{"foo", "megafoo"}, {"bar", "hyperbar"},
                           {"fancy", "stuff"}});
    json.verifyDimensions(1, "outer.temp.val2",
                          {{"foo", "megafoo"}, {"bar", "hyperbar"},
                           {"baz", "superbaz"}, {"fancy", "stuff"}});
}

namespace {

struct DimensionOverridableTestMetricSet : MetricSet
{
    DoubleValueMetric val;

    DimensionOverridableTestMetricSet(const std::string& dimValue, MetricSet* owner = nullptr);
    ~DimensionOverridableTestMetricSet() override;
};

DimensionOverridableTestMetricSet::DimensionOverridableTestMetricSet(const std::string& dimValue, MetricSet* owner)
    : MetricSet("temp", {{"foo", dimValue}}, "", owner),
      val("val", {}, "val desc", this)
{ }
DimensionOverridableTestMetricSet::~DimensionOverridableTestMetricSet() = default;

struct SameNamesTestMetricSet : MetricSet
{
    DimensionOverridableTestMetricSet set1;
    DimensionOverridableTestMetricSet set2;

    SameNamesTestMetricSet();
    ~SameNamesTestMetricSet();
};

SameNamesTestMetricSet::SameNamesTestMetricSet()
    : MetricSet("outer", {{"fancy", "stuff"}}, ""),
      set1("bar", this),
      set2("baz", this)
{ }
SameNamesTestMetricSet::~SameNamesTestMetricSet() = default;

}

TEST_F(MetricManagerTest, json_output_can_have_multiple_sets_with_same_name)
{
    SameNamesTestMetricSet mset;
    MetricSnapshotTestFixture fixture(*this, mset);

    mset.set1.val.addValue(2);
    mset.set2.val.addValue(5);

    fixture.takeSnapshotsOnce();
    std::string actual = fixture.renderLastSnapshotAsJson();
    JsonMetricWrapper json(actual);

    // Note the identical names. Only difference is the dimensions per set.
    json.verifyDimensions(0, "outer.temp.val",
                          {{"foo", "bar"}, {"fancy", "stuff"}});
    json.verifyDimensions(1, "outer.temp.val",
                          {{"foo", "baz"}, {"fancy", "stuff"}});
}

TEST_F(MetricManagerTest, test_text_output)
{
    MetricManager mm(std::make_unique<FakeTimer>(1000));
    TestMetricSet mySet;
    {
        MetricLockGuard lockGuard(mm.getMetricLock());
        mm.registerMetric(lockGuard, mySet.set);
    }
        // Adding metrics to have some values in them
    mySet.val6.addValue(2);
    mySet.val9.val1.addValue(4);
    mySet.val10.count.inc();
    mySet.val10.a.val1.addValue(7);
    mySet.val10.a.val2.addValue(2);
    mySet.val10.b.val1.addValue(1);
        // Initialize metric manager to get snapshots created.
    mm.init(ConfigUri("raw:"
                      "consumer[2]\n"
                      "consumer[0].name snapper\n"
                      "consumer[0].tags[1]\n"
                      "consumer[0].tags[0] snaptest\n"
                      "consumer[1].name log\n"
                      "consumer[1].tags[1]\n"
                      "consumer[1].tags[0] snaptest\n"));
    std::string expected(
        "snapshot \"Active metrics showing updates since last snapshot\" from 1970-01-01 00:16:40.000 UTC to 1970-01-01 00:00:00.000 UTC period 0\n"
        "temp.val6 average=2 last=2 min=2 max=2 count=1 total=2\n"
        "temp.sub.val1 average=4 last=4 min=4 max=4 count=1 total=4\n"
        "temp.sub.valsum average=4 last=4 min=4 max=4 count=1 total=4\n"
        "temp.multisub.count count=1\n"
        "temp.multisub.a.val1 average=7 last=7 min=7 max=7 count=1 total=7\n"
        "temp.multisub.a.valsum average=9 last=9\n"
        "temp.multisub.b.val1 average=1 last=1 min=1 max=1 count=1 total=1\n"
        "temp.multisub.b.valsum average=1 last=1 min=1 max=1 count=1 total=1\n"
        "temp.multisub.sum.val1 average=8 last=8\n"
        "temp.multisub.sum.val2 average=2 last=2 min=2 max=2 count=1 total=2\n"
        "temp.multisub.sum.valsum average=10 last=10");
    std::ostringstream ost;
    TextWriter writer(ost, 300s, ".*", true);
    {
        MetricLockGuard lockGuard(mm.getMetricLock());
        mm.visit(lockGuard, mm.getActiveMetrics(lockGuard), writer, "snapper");
    }
    std::string actual(ost.str());
    // Not bothering to match all the nitty gritty details as it will test
    // more than it needs to. Just be here in order to check on XML output
    // easily if needed.
    EXPECT_EQ(expected, actual);
}

TEST_F(MetricManagerTest, text_output_supports_dimensions)
{
    NestedDimensionTestMetricSet mset;
    MetricSnapshotTestFixture fixture(*this, mset);

    mset.nestedSet.val1.addValue(2);
    mset.nestedSet.val2.inc();

    fixture.takeSnapshotsOnce();
    std::string actual = fixture.renderLastSnapshotAsText("outer.*temp.*val");
    std::string expected(
            "snapshot \"5 minute\" from 1970-01-01 00:16:40.000 UTC to 1970-01-01 00:21:40.000 UTC period 300\n"
            "outer{fancy:stuff}.temp{bar:hyperbar,foo:megafoo}.val1 average=2 last=2 min=2 max=2 count=1 total=2\n"
            "outer{fancy:stuff}.temp{bar:hyperbar,foo:megafoo}.val2{baz:superbaz} count=1");
    EXPECT_EQ(expected, actual);
}

namespace {
    struct MyUpdateHook : public UpdateHook {
        std::ostringstream& _output;
        std::mutex&         _output_mutex;
        FakeTimer&          _timer;

        MyUpdateHook(std::ostringstream& output, std::mutex& output_mutex, const char* name, vespalib::system_clock::duration period, FakeTimer& timer)
            : UpdateHook(name, period),
              _output(output),
              _output_mutex(output_mutex),
              _timer(timer)
        {}
        ~MyUpdateHook() override = default;

        void updateMetrics(const MetricLockGuard & ) override {
            std::lock_guard lock(_output_mutex); // updateMetrics() called from metric manager thread
            _output << vespalib::count_s(_timer.getTime().time_since_epoch()) << ": " << getName() << " called\n";
        }
    };
}

TEST_F(MetricManagerTest, test_update_hooks)
{
    std::mutex output_mutex;
    std::ostringstream output;
    auto timerImpl = std::make_unique<FakeTimer>(1000);
    FakeTimer & timer = *timerImpl;
        // Add a metric set just so one exist
    TestMetricSet mySet;
    MetricManager mm(std::move(timerImpl));
    {
        MetricLockGuard lockGuard(mm.getMetricLock());
        mm.registerMetric(lockGuard, mySet.set);
    }

    MyUpdateHook preInitShort(output, output_mutex, "BIS", 5s, timer);
    MyUpdateHook preInitLong(output, output_mutex, "BIL", 300s, timer);
    MyUpdateHook preInitInfinite(output, output_mutex, "BII", 0s, timer);
    mm.addMetricUpdateHook(preInitShort);
    mm.addMetricUpdateHook(preInitLong);
    mm.addMetricUpdateHook(preInitInfinite);

    // All hooks should get called during initialization

    // Initialize metric manager to get snapshots created.
    output << "Running init\n";
    mm.init(ConfigUri("raw:"
                      "consumer[2]\n"
                      "consumer[0].name snapper\n"
                      "consumer[0].tags[1]\n"
                      "consumer[0].tags[0] snaptest\n"
                      "consumer[1].name log\n"
                      "consumer[1].tags[1]\n"
                      "consumer[1].tags[0] snaptest\n"));
    output << "Init done\n";

    MyUpdateHook postInitShort(output, output_mutex, "AIS", 5s, timer);
    MyUpdateHook postInitLong(output, output_mutex, "AIL", 400s, timer);
    MyUpdateHook postInitInfinite(output, output_mutex, "AII", 0s, timer);
    mm.addMetricUpdateHook(postInitShort);
    mm.addMetricUpdateHook(postInitLong);
    mm.addMetricUpdateHook(postInitInfinite);

    // After 5 seconds the short ones should get another.

    timer.set_time(1006);
    waitForTimeProcessed(mm, 1006s);

    // After 4 more seconds the short ones should get another
    // since last update was a second late. (Stable periods, process time
    // should not affect how often they are updated)

    timer.set_time(1010);
    waitForTimeProcessed(mm, 1010s);

    // Bumping considerably ahead, such that next update is in the past,
    // we should only get one update called in this period.

    timer.set_time(1200);
    waitForTimeProcessed(mm, 1200s);

    // No updates at this time.
    timer.set_time(1204);
    waitForTimeProcessed(mm, 1204s);

    // Give all hooks an update
    mm.updateMetrics();

    // Last update should not have interfered with periods
    timer.set_time(1205);
    waitForTimeProcessed(mm, 1205s);

    // Time is just ahead of a snapshot.
    timer.set_time(1299);
    waitForTimeProcessed(mm, 1299s);

    // At time 1300 we are at a 5 minute snapshot bump
    // All hooks should thus get an update. The one with matching period
    // should only get one
    timer.set_time(1300);
    waitForTimeProcessed(mm, 1300s);

    // The snapshot time currently doesn't count for the metric at period
    // 400. It will get an event at this time.
    timer.set_time(1450);
    waitForTimeProcessed(mm, 1450s);

    std::string expected(
        "Running init\n"
        "1000: BIS called\n"
        "1000: BIL called\n"
        "Init done\n"
        "1006: BIS called\n"
        "1006: AIS called\n"
        "1010: BIS called\n"
        "1010: AIS called\n"
        "1200: BIS called\n"
        "1200: AIS called\n"
        "1204: BIS called\n"
        "1204: BIL called\n"
        "1204: AIS called\n"
        "1204: AIL called\n"
        "1204: BII called\n"
        "1204: AII called\n"
        "1205: BIS called\n"
        "1205: AIS called\n"
        "1299: BIS called\n"
        "1299: AIS called\n"
        "1300: BIS called\n"
        "1300: BIL called\n"
        "1300: AIS called\n"
        "1300: AIL called\n"
        "1300: BII called\n"
        "1300: AII called\n"
        "1450: BIS called\n"
        "1450: AIS called\n"
        "1450: AIL called\n"
    );
    {
        std::lock_guard lock(output_mutex); // Need to ensure we observe all writes by metric mgr thread
        std::string actual(output.str());
        EXPECT_EQ(expected, actual);
    }
}

}
