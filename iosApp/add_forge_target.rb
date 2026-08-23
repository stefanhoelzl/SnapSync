# Adds the SnapSyncForge target to iosApp.xcodeproj.
#
# Written as a script rather than hand-edited into project.pbxproj because that file is a UUID-keyed
# plist where an edit that LOOKS right can silently break a different target — and the target it would
# break here is `iosApp`, which `screenshots.yml` and every release build depend on.
#
# Idempotent: re-running it removes and rebuilds the target, so it can be re-applied after a merge that
# touched the project file.
#
#   gem install --user-install xcodeproj && ruby iosApp/add_forge_target.rb
require 'xcodeproj'

PROJECT = File.join(__dir__, 'iosApp.xcodeproj')
TARGET_NAME = 'SnapSyncForge'

project = Xcodeproj::Project.open(PROJECT)

# ---- idempotency: drop any previous version of the target and its group
project.targets.select { |t| t.name == TARGET_NAME }.each do |t|
  t.build_configuration_list.build_configurations.each(&:remove_from_project)
  t.build_configuration_list.remove_from_project
  t.remove_from_project
end
project.main_group.children.select { |g| g.respond_to?(:name) && g.name == TARGET_NAME }
       .each(&:remove_from_project)
project.root_object.attributes['TargetAttributes']&.reject! { |_, v| v['__forge'] }

app = project.targets.find { |t| t.name == 'iosApp' } or abort 'iosApp target not found'

target = project.new_target(:application, TARGET_NAME, :ios, app.deployment_target)

# ---- build settings
#
# Mirrors the app where a capture must look identical (deployment target, Swift version, device family)
# and diverges everywhere the app carries a capability this binary must not have. In particular there is
# no CODE_SIGN_ENTITLEMENTS pointing at the app's file: this target has its own, empty of grants.
target.build_configurations.each do |config|
  config.build_settings.merge!(
    'PRODUCT_NAME' => TARGET_NAME,
    'PRODUCT_BUNDLE_IDENTIFIER' => 'app.snapsync.forge',
    'INFOPLIST_FILE' => 'SnapSyncForge/Info.plist',
    'CODE_SIGN_ENTITLEMENTS' => 'SnapSyncForge/SnapSyncForge.entitlements',
    'SWIFT_VERSION' => '5.0',
    'TARGETED_DEVICE_FAMILY' => '1',
    'GENERATE_INFOPLIST_FILE' => 'NO',
    'CURRENT_PROJECT_VERSION' => '1',
    'ENABLE_USER_SCRIPT_SANDBOXING' => 'NO',
    # The Kotlin framework is produced by the Gradle phase below into this directory.
    'FRAMEWORK_SEARCH_PATHS' => ['$(inherited)', '$(SRCROOT)/../app/ios/forge/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)'],
    'LD_RUNPATH_SEARCH_PATHS' => ['$(inherited)', '@executable_path/Frameworks'],
    # A screenshot binary is never distributed, so it never needs a real identity. Signing is left
    # unset rather than automatic: the simulator build that screenshots.yml runs uses
    # CODE_SIGNING_ALLOWED=NO, and leaving it automatic would make a device build silently demand a
    # provisioning profile that nobody has minted.
    'CODE_SIGN_STYLE' => 'Manual',
    'CODE_SIGN_IDENTITY' => '',
    'CODE_SIGNING_REQUIRED' => 'NO',
    'CODE_SIGNING_ALLOWED' => 'NO',
    # Matches the app and extension targets. The Kotlin modules declare `iosSimulatorArm64` only, so a
    # `generic/platform=iOS Simulator` destination — which is what screenshots.yml uses — would otherwise
    # ask for an x86_64 slice that no Kotlin target produces, and the Compose plugin's iOS resource sync
    # fails with "Unknown iOS simulator arch: 'x86_64'". Without this line the failure surfaces inside a
    # Gradle phase, which reads as a Kotlin problem rather than a missing build setting.
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'x86_64',
  )
end

# ---- sources
group = project.main_group.new_group(TARGET_NAME, TARGET_NAME)
swift = group.new_reference('ForgeApp.swift')
group.new_reference('Info.plist')
group.new_reference('SnapSyncForge.entitlements')
target.add_file_references([swift])

# ---- the Gradle phase that builds and embeds the Kotlin framework
#
# `-Psnapsync.forge=true` is passed HERE rather than left to the caller: without it :app:ios:forge
# compiles nothing and :ui:presentation carries no preset table, so a build of this target without the
# property would fail in a way that reads as a broken project rather than a missing flag.
phase = target.new_shell_script_build_phase('Build Kotlin framework (SnapSyncForgeKit)')
phase.shell_script = <<~SH
  cd "$SRCROOT/.."
  ./gradlew :app:ios:forge:embedAndSignAppleFrameworkForXcode -Psnapsync.forge=true
SH
# Run it before compiling Swift, which imports the framework it produces.
target.build_phases.unshift(target.build_phases.delete(phase))

project.save

puts "added #{TARGET_NAME}"
puts "targets now: #{project.targets.map(&:name).join(', ')}"
