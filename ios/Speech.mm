#import "Speech.h"

using namespace JS::NativeSpeech;

@implementation Speech
{
  NSDictionary *defaultOptions;
}

RCT_EXPORT_MODULE();

+ (BOOL)requiresMainQueueSetup {
  return NO;
}

- (instancetype)init {
  self = [super init];

  if (self) {
    _synthesizer = [[AVSpeechSynthesizer alloc] init];
    _synthesizer.delegate = self;
    
    defaultOptions = @{
      @"pitch": @(1.0),
      @"volume": @(1.0),
      @"silentMode": @"obey",
      @"rate": @(AVSpeechUtteranceDefaultSpeechRate),
      @"language": [AVSpeechSynthesisVoice currentLanguageCode] ?: @"en-US"
    };
    
    self.globalOptions = [defaultOptions copy];
  }
  return self;
}

- (void)configureAudioSessionWithOptions:(NSDictionary *)options {
  NSError *error = nil;
  NSString *silentMode = options[@"silentMode"] ?: @"obey";

  if ([silentMode isEqualToString:@"ignore"]) {
    [[AVAudioSession sharedInstance] setCategory:AVAudioSessionCategoryPlayback error:&error];
  } else if ([silentMode isEqualToString:@"respect"]) {
    [[AVAudioSession sharedInstance] setCategory:AVAudioSessionCategoryAmbient error:&error];
  }
  if (error) {
    NSLog(@"[Speech] AVAudioSession setCategory error: %@", error.localizedDescription);
  }
}

- (NSDictionary *)getEventData:(AVSpeechUtterance *)utterance {
  return @{
    @"id": @(utterance.hash)
  };
}

- (NSDictionary *)getVoiceItem:(AVSpeechSynthesisVoice *)voice {
  return @{
    @"name": voice.name,
    @"language": voice.language,
    @"identifier": voice.identifier,
    @"quality": voice.quality == AVSpeechSynthesisVoiceQualityEnhanced ? @"Enhanced" : @"Default"
  };
}

- (NSDictionary *)getValidatedOptions:(VoiceOptions &)options {
  NSMutableDictionary *validatedOptions = [NSMutableDictionary new];
  
  if (options.voice()) {
    validatedOptions[@"voice"] = options.voice();
  }
  if (options.language()) {
    validatedOptions[@"language"] = options.language();
  }
  if (options.silentMode()) {
    validatedOptions[@"silentMode"] = options.silentMode();
  }
  if (options.pitch()) {
    float pitch = MAX(0.5, MIN(2.0, options.pitch().value()));
    validatedOptions[@"pitch"] = @(pitch);
  }
  if (options.volume()) {
    float volume = MAX(0, MIN(1.0, options.volume().value()));
    validatedOptions[@"volume"] = @(volume);
  }
  if (options.rate()) {
    float rate = MAX(AVSpeechUtteranceMinimumSpeechRate,
                    MIN(AVSpeechUtteranceMaximumSpeechRate, options.rate().value()));
    validatedOptions[@"rate"] = @(rate);
  }
  return validatedOptions;
}

- (AVSpeechUtterance *)getDefaultUtterance:(NSString *)text {
  AVSpeechUtterance *utterance = [[AVSpeechUtterance alloc] initWithString:text];
  
  if (self.globalOptions[@"voice"]) {
    AVSpeechSynthesisVoice *voice = [AVSpeechSynthesisVoice voiceWithIdentifier:self.globalOptions[@"voice"]];
    if (voice) {
      utterance.voice = voice;
    } else if (self.globalOptions[@"language"]) {
      utterance.voice = [AVSpeechSynthesisVoice voiceWithLanguage:self.globalOptions[@"language"]];
    }
  } else {
    utterance.voice = [AVSpeechSynthesisVoice voiceWithLanguage:self.globalOptions[@"language"]];
  }
  utterance.rate = [self.globalOptions[@"rate"] floatValue];
  utterance.volume = [self.globalOptions[@"volume"] floatValue];
  utterance.pitchMultiplier = [self.globalOptions[@"pitch"] floatValue];

  return utterance;
}

- (void)initialize:(VoiceOptions &)options {
  NSMutableDictionary *newOptions = [NSMutableDictionary dictionaryWithDictionary:self.globalOptions];
  NSDictionary *validatedOptions = [self getValidatedOptions:options];
  [newOptions addEntriesFromDictionary:validatedOptions];
  self.globalOptions = newOptions;
}

- (void)reset {
  self.globalOptions = [defaultOptions copy];
}

- (void)getAvailableVoices:(NSString *)language
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject
{
  NSMutableArray *voicesArray = [NSMutableArray new];
  NSArray *speechVoices = [AVSpeechSynthesisVoice speechVoices];
  
  if (language) {
    NSString *lowercaseLanguage = [language lowercaseString];
    
    for (AVSpeechSynthesisVoice *voice in speechVoices) {
      NSString *voiceLanguage = [voice.language lowercaseString];

      if ([voiceLanguage hasPrefix:lowercaseLanguage]) {
        [voicesArray addObject:[self getVoiceItem:voice]];
      }
    }
  } else {
    for (AVSpeechSynthesisVoice *voice in speechVoices) {
      [voicesArray addObject:[self getVoiceItem:voice]];
    }
  }
  resolve(voicesArray);
}

- (void)getEngines:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  resolve(@[]);
}

- (void)setEngine:(NSString *)engineName
          resolve:(RCTPromiseResolveBlock)resolve
          reject:(RCTPromiseRejectBlock)reject {
  resolve(nil);
}

- (void)isSpeaking:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  BOOL speaking = self.synthesizer.isSpeaking;
  resolve(@(speaking));
}

- (void)stop:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  if (self.synthesizer.isSpeaking) {
    [self.synthesizer stopSpeakingAtBoundary:AVSpeechBoundaryImmediate];
  }
  resolve(nil);
}

- (void)pause:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  if (self.synthesizer.isSpeaking && !self.synthesizer.isPaused) {
    BOOL paused = [self.synthesizer pauseSpeakingAtBoundary:AVSpeechBoundaryImmediate];
    resolve(@(paused));
  } else {
    resolve(@(false));
  }
}

- (void)resume:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  if (self.synthesizer.isPaused) {
    BOOL resumed = [self.synthesizer continueSpeaking];
    resolve(@(resumed));
  } else {
    resolve(@(false));
  }
}

- (void)speak:(NSString *)text
    resolve:(RCTPromiseResolveBlock)resolve
    reject:(RCTPromiseRejectBlock)reject
{
  if (!text) {
    reject(@"speech_error", @"Text cannot be null", nil);
    return;
  }

  AVSpeechUtterance *utterance;
 
  @try {
    [self configureAudioSessionWithOptions:self.globalOptions];

    utterance = [self getDefaultUtterance:text];
    [self.synthesizer speakUtterance:utterance];
    resolve(nil);
  }
  @catch (NSException *exception) {
    [self emitOnError:[self getEventData:utterance]];
    reject(@"speech_error", exception.reason, nil);
  }
}

- (void)speakWithOptions:(NSString *)text
    options:(VoiceOptions &)options
    resolve:(RCTPromiseResolveBlock)resolve
    reject:(RCTPromiseRejectBlock)reject
{
  if (!text) {
    reject(@"speech_error", @"Text cannot be null", nil);
    return;
  }
  
  AVSpeechUtterance *utterance;

  @try {
    NSDictionary *validatedOptions = [self getValidatedOptions:options];
    [self configureAudioSessionWithOptions:validatedOptions];
    
    utterance = [self getDefaultUtterance:text];

    if (validatedOptions[@"voice"]) {
      AVSpeechSynthesisVoice *voice = [AVSpeechSynthesisVoice voiceWithIdentifier:validatedOptions[@"voice"]];
      if (voice) {
        utterance.voice = voice;
      }
    } else if (validatedOptions[@"language"]) {
      utterance.voice = [AVSpeechSynthesisVoice voiceWithLanguage:validatedOptions[@"language"]];
    }
    if (validatedOptions[@"pitch"]) {
      utterance.pitchMultiplier = [validatedOptions[@"pitch"] floatValue];
    }
    if (validatedOptions[@"volume"]) {
      utterance.volume = [validatedOptions[@"volume"] floatValue];
    }
    if (validatedOptions[@"rate"]) {
      utterance.rate = [validatedOptions[@"rate"] floatValue];
    }

    [self.synthesizer speakUtterance:utterance];
    resolve(nil);
  }
  @catch (NSException *exception) {
    [self emitOnError:[self getEventData:utterance]];
    reject(@"speech_error", exception.reason, nil);
  }
}

- (void)speechSynthesizer:(AVSpeechSynthesizer *)synthesizer
  didStartSpeechUtterance:(AVSpeechUtterance *)utterance {
  [self emitOnStart:[self getEventData:utterance]];
}

- (void)speechSynthesizer:(AVSpeechSynthesizer *)synthesizer
  willSpeakRangeOfSpeechString:(NSRange)characterRange utterance:(AVSpeechUtterance *)utterance {
  [self emitOnProgress:@{
    @"id": @(utterance.hash),
    @"length": @(characterRange.length),
    @"location": @(characterRange.location)
  }];
}

- (void)speechSynthesizer:(AVSpeechSynthesizer *)synthesizer
  didFinishSpeechUtterance:(AVSpeechUtterance *)utterance {
  [self emitOnFinish:[self getEventData:utterance]];
}

- (void)speechSynthesizer:(AVSpeechSynthesizer *)synthesizer
  didPauseSpeechUtterance:(nonnull AVSpeechUtterance *)utterance {
  [self emitOnPause:[self getEventData:utterance]];
}

- (void)speechSynthesizer:(AVSpeechSynthesizer *)synthesizer
  didContinueSpeechUtterance:(nonnull AVSpeechUtterance *)utterance {
  [self emitOnResume:[self getEventData:utterance]];
}

- (void)speechSynthesizer:(AVSpeechSynthesizer *)synthesizer
  didCancelSpeechUtterance:(AVSpeechUtterance *)utterance {
  [self emitOnStopped:[self getEventData:utterance]];
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
    return std::make_shared<facebook::react::NativeSpeechSpecJSI>(params);
}

@end
