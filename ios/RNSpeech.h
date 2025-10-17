#import <RNSpeechSpec/RNSpeechSpec.h>
#import "AVFoundation/AVFoundation.h"

@interface RNSpeech : NativeSpeechSpecBase <NativeSpeechSpec, AVSpeechSynthesizerDelegate>
@property (nonatomic, strong) AVSpeechSynthesizer *synthesizer;
@property (nonatomic, strong) NSDictionary *globalOptions;
@end
