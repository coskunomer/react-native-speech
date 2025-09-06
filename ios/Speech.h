#import <RNSpeechSpec/RNSpeechSpec.h>
#import "AVFoundation/AVFoundation.h"

@interface Speech : NativeSpeechSpecBase <NativeSpeechSpec, AVSpeechSynthesizerDelegate>
@property (nonatomic, strong) AVSpeechSynthesizer *synthesizer;
@property (nonatomic, strong) NSDictionary *globalOptions;
@end
