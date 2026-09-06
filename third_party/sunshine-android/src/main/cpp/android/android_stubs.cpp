#include "android_bridge.h"

#include <atomic>
#include <cstdlib>
#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <tuple>
#include <variant>
#include <vector>

#include <boost/property_tree/json_parser.hpp>
#include <boost/property_tree/ptree.hpp>

#include "src/config.h"
#include "src/display_device.h"
#include "src/entry_handler.h"
#include "src/globals.h"
#include "src/httpcommon.h"
#include "src/process.h"
#include "src/system_tray.h"
#include "src/uuid.h"

using namespace std::literals;

namespace {
  class noop_deinit_t: public platf::deinit_t {};

  std::vector<proc::ctx_t> default_apps() {
    proc::ctx_t app {};
    app.name = "Android Window";
    app.id = "1";
    app.image_path = DEFAULT_APP_IMAGE_PATH;
    return {std::move(app)};
  }
}

namespace http {
  std::string unique_id = uuid_util::uuid_t::generate().string();
  net::net_e origin_web_ui_allowed = net::LAN;

  int init() {
    if (unique_id.empty()) {
      unique_id = uuid_util::uuid_t::generate().string();
    }
    return 0;
  }

  int create_creds(const std::string &, const std::string &) {
    return 0;
  }

  int save_user_creds(const std::string &, const std::string &, const std::string &, bool) {
    return 0;
  }

  int reload_user_creds(const std::string &) {
    return 0;
  }

  bool download_file(const std::string &, const std::string &, long) {
    return false;
  }

  std::string url_escape(const std::string &url) {
    return url;
  }

  std::string url_get_host(const std::string &) {
    return {};
  }
}

namespace display_device {
  std::unique_ptr<platf::deinit_t> init(const std::filesystem::path &, const config::video_t &) {
    return std::make_unique<noop_deinit_t>();
  }

  std::string map_output_name(const std::string &output_name) {
    return output_name;
  }

  void configure_display(const config::video_t &, const rtsp_stream::launch_session_t &) {
  }

  void configure_display(const SingleDisplayConfiguration &) {
  }

  void revert_configuration() {
  }

  bool reset_persistence() {
    return true;
  }

  EnumeratedDeviceList enumerate_devices() {
    return {};
  }

  std::variant<failed_to_parse_tag_t, configuration_disabled_tag_t, SingleDisplayConfiguration> parse_configuration(const config::video_t &, const rtsp_stream::launch_session_t &) {
    return configuration_disabled_tag_t {};
  }
}

namespace system_tray {
  void tray_open_ui_cb(tray_menu *) {}
  void tray_donate_github_cb(tray_menu *) {}
  void tray_donate_patreon_cb(tray_menu *) {}
  void tray_donate_paypal_cb(tray_menu *) {}
  void tray_reset_display_device_config_cb(tray_menu *) {}
  void tray_restart_cb(tray_menu *) {}
  void tray_quit_cb(tray_menu *) {}

  int init_tray() {
    return 0;
  }

  int process_tray_events() {
    return 0;
  }

  int end_tray() {
    return 0;
  }

  void update_tray_playing(std::string app_name) {
    (void) app_name;
  }

  void update_tray_pausing(std::string app_name) {
    sunshine_android::call_client_disconnected(app_name.empty() ? "Moonlight" : app_name);
    proc::proc.terminate();
  }

  void update_tray_stopped(std::string app_name) {
    sunshine_android::call_client_disconnected(app_name.empty() ? "Moonlight" : app_name);
    proc::proc.terminate();
  }

  void update_tray_require_pin() {
    sunshine_android::call_pin_requested();
  }

  int init_tray_threaded() {
    return 0;
  }
}

namespace lifetime {
  char **argv = nullptr;
  std::atomic_int desired_exit_code = 0;

  void exit_sunshine(int exit_code, bool) {
    desired_exit_code = exit_code;
    if (mail::man) {
      mail::man->event<bool>(mail::shutdown)->raise(true);
    }
  }

  void debug_trap() {
    std::abort();
  }

  char **get_argv() {
    return argv;
  }
}

void launch_ui(const std::optional<std::string> &) {
}

void log_publisher_data() {
}

namespace proc {
  proc_t proc {boost::this_process::environment(), default_apps()};

  int proc_t::execute(int app_id, std::shared_ptr<rtsp_stream::launch_session_t>) {
    _app_id = app_id > 0 ? app_id : 1;
    return 0;
  }

  int proc_t::running() {
    return _app_id;
  }

  proc_t::~proc_t() = default;

  const std::vector<ctx_t> &proc_t::get_apps() const {
    return _apps;
  }

  std::vector<ctx_t> &proc_t::get_apps() {
    return _apps;
  }

  std::string proc_t::get_app_image(int) {
    return DEFAULT_APP_IMAGE_PATH;
  }

  std::string proc_t::get_last_run_app_name() {
    return "Android Window";
  }

  void proc_t::terminate() {
    _app_id = 0;
  }

  std::tuple<std::string, std::string> calculate_app_id(const std::string &app_name, std::string, int index) {
    auto id = std::to_string(index + 1);
    return {app_name + "-" + id, id};
  }

  bool check_valid_png(const std::filesystem::path &) {
    return false;
  }

  std::string validate_app_image_path(std::string app_image_path) {
    return app_image_path.empty() ? DEFAULT_APP_IMAGE_PATH : app_image_path;
  }

  void refresh(const std::string &) {
  }

  std::optional<proc_t> parse(const std::string &) {
    return std::nullopt;
  }

  std::unique_ptr<platf::deinit_t> init() {
    return std::make_unique<noop_deinit_t>();
  }

  void terminate_process_group(boost::process::v1::child &, boost::process::v1::group &, std::chrono::seconds) {
  }
}
