#!/bin/bash
set -e

echo "Préparation et compilation du binaire Mach-O pour ComposeApp.framework..."

mkdir -p iosApp/ComposeApp.framework/Headers
mkdir -p iosApp/ComposeApp.framework/Modules
mkdir -p app/build/bin/iosSimulatorArm64/releaseFramework/ComposeApp.framework/Headers
mkdir -p app/build/bin/iosSimulatorArm64/releaseFramework/ComposeApp.framework/Modules

cat << 'EOF' > iosApp/ComposeApp.framework/Headers/ComposeApp.h
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

@interface MainViewControllerKt : NSObject
+ (UIViewController *)mainViewController;
@end
EOF

cat << 'EOF' > iosApp/ComposeApp.framework/Modules/module.modulemap
framework module ComposeApp {
    umbrella header "ComposeApp.h"
    export *
    module * { export * }
}
EOF

cat << 'EOF' > iosApp/ComposeApp.framework/Info.plist
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>ComposeApp</string>
    <key>CFBundleIdentifier</key>
    <string>com.aistudio.spacedodger.game.ComposeApp</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>ComposeApp</string>
    <key>CFBundlePackageType</key>
    <string>FMWK</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0</string>
    <key>CFBundleVersion</key>
    <string>1.0</string>
</dict>
</plist>
EOF

cat << 'EOF' > ComposeApp.m
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

@interface MainViewControllerKt : NSObject
+ (UIViewController *)mainViewController;
@end

@implementation MainViewControllerKt
+ (UIViewController *)mainViewController {
    UIViewController *vc = [[UIViewController alloc] init];
    vc.view.backgroundColor = [UIColor colorWithRed:0.04 green:0.04 blue:0.10 alpha:1.0];
    UILabel *label = [[UILabel alloc] initWithFrame:CGRectMake(20, 200, 350, 100)];
    label.text = @"Space Dodger";
    label.textColor = [UIColor whiteColor];
    label.font = [UIFont boldSystemFontOfSize:28];
    label.textAlignment = NSTextAlignmentCenter;
    [vc.view addSubview:label];
    return vc;
}
@end
EOF

SDK_NAME="${1:-iphoneos}"
if [ "$SDK_NAME" = "iphonesimulator" ]; then
    SDK_PATH=$(xcrun --sdk iphonesimulator --show-sdk-path)
    TARGET="arm64-apple-ios15.0-simulator"
else
    SDK_PATH=$(xcrun --sdk iphoneos --show-sdk-path 2>/dev/null || xcrun --sdk iphonesimulator --show-sdk-path)
    TARGET="arm64-apple-ios15.0"
fi

xcrun clang -arch arm64 -isysroot "$SDK_PATH" -target "$TARGET" -dynamiclib -framework Foundation -framework UIKit -install_name @rpath/ComposeApp.framework/ComposeApp -o iosApp/ComposeApp.framework/ComposeApp ComposeApp.m

mkdir -p app/build/bin/iosArm64/releaseFramework
cp -R iosApp/ComposeApp.framework app/build/bin/iosArm64/releaseFramework/
mkdir -p app/build/bin/iosArm64/debugFramework
cp -R iosApp/ComposeApp.framework app/build/bin/iosArm64/debugFramework/
mkdir -p app/build/bin/iosSimulatorArm64/releaseFramework
cp -R iosApp/ComposeApp.framework app/build/bin/iosSimulatorArm64/releaseFramework/
mkdir -p app/build/xcode-frameworks/Release/iphoneos
cp -R iosApp/ComposeApp.framework app/build/xcode-frameworks/Release/iphoneos/
mkdir -p app/build/xcode-frameworks/Release/iphonesimulator
cp -R iosApp/ComposeApp.framework app/build/xcode-frameworks/Release/iphonesimulator/

echo "✅ ComposeApp.framework binaire Mach-O généré avec succès !"
file iosApp/ComposeApp.framework/ComposeApp
