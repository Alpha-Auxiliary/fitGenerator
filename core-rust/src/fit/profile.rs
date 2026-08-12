//! Minimal FIT profile identifiers used by the encoder.

pub mod global {
    pub const FILE_ID: u16 = 0;
    pub const SESSION: u16 = 18;
    pub const LAP: u16 = 19;
    pub const RECORD: u16 = 20;
    pub const EVENT: u16 = 21;
    pub const DEVICE_INFO: u16 = 23;
    pub const ACTIVITY: u16 = 34;
}

pub mod field {
    pub mod file {
        pub const TYPE: u8 = 0;
        pub const MANUFACTURER: u8 = 1;
        pub const PRODUCT: u8 = 2;
        pub const SERIAL: u8 = 3;
        pub const TIME_CREATED: u8 = 4;
    }

    pub mod device {
        pub const TIMESTAMP: u8 = 253;
        pub const DEVICE_INDEX: u8 = 0;
        pub const MANUFACTURER: u8 = 2;
        pub const PRODUCT: u8 = 4;
    }

    pub mod event {
        pub const TIMESTAMP: u8 = 253;
        pub const EVENT: u8 = 0;
        pub const EVENT_TYPE: u8 = 1;
    }

    pub mod record {
        pub const TIMESTAMP: u8 = 253;
        pub const POSITION_LAT: u8 = 0;
        pub const POSITION_LONG: u8 = 1;
        pub const HEART_RATE: u8 = 3;
        pub const DISTANCE: u8 = 5;
        pub const SPEED: u8 = 6;
    }

    pub mod lap {
        pub const TIMESTAMP: u8 = 253;
        pub const EVENT: u8 = 0;
        pub const EVENT_TYPE: u8 = 1;
        pub const START_TIME: u8 = 2;
        pub const START_POSITION_LAT: u8 = 3;
        pub const START_POSITION_LONG: u8 = 4;
        pub const END_POSITION_LAT: u8 = 5;
        pub const END_POSITION_LONG: u8 = 6;
        pub const TOTAL_ELAPSED_TIME: u8 = 7;
        pub const TOTAL_TIMER_TIME: u8 = 8;
        pub const TOTAL_DISTANCE: u8 = 9;
        pub const AVG_SPEED: u8 = 13;
        pub const MAX_SPEED: u8 = 14;
        pub const AVG_HEART_RATE: u8 = 15;
        pub const MAX_HEART_RATE: u8 = 16;
    }

    pub mod session {
        pub const TIMESTAMP: u8 = 253;
        pub const EVENT: u8 = 0;
        pub const EVENT_TYPE: u8 = 1;
        pub const START_TIME: u8 = 2;
        pub const START_POSITION_LAT: u8 = 3;
        pub const START_POSITION_LONG: u8 = 4;
        pub const SPORT: u8 = 5;
        pub const SUB_SPORT: u8 = 6;
        pub const TOTAL_ELAPSED_TIME: u8 = 7;
        pub const TOTAL_TIMER_TIME: u8 = 8;
        pub const TOTAL_DISTANCE: u8 = 9;
        pub const AVG_SPEED: u8 = 14;
        pub const MAX_SPEED: u8 = 15;
        pub const AVG_HEART_RATE: u8 = 16;
        pub const MAX_HEART_RATE: u8 = 17;
        pub const FIRST_LAP_INDEX: u8 = 25;
        pub const NUM_LAPS: u8 = 26;
    }

    pub mod activity {
        pub const TIMESTAMP: u8 = 253;
        pub const TOTAL_TIMER_TIME: u8 = 0;
        pub const NUM_SESSIONS: u8 = 1;
        pub const TYPE: u8 = 2;
        pub const EVENT: u8 = 3;
        pub const EVENT_TYPE: u8 = 4;
    }
}

pub mod base_type {
    pub const ENUM: u8 = 0x00;
    pub const UINT8: u8 = 0x02;
    pub const UINT16: u8 = 0x84;
    pub const SINT32: u8 = 0x85;
    pub const UINT32: u8 = 0x86;
    pub const UINT32Z: u8 = 0x8c;
}

pub mod value {
    pub mod file_type {
        pub const ACTIVITY: u8 = 4;
    }

    pub mod manufacturer {
        pub const DEVELOPMENT: u16 = 255;
    }

    pub mod sport {
        pub const RUNNING: u8 = 1;
    }

    pub mod sub_sport {
        pub const GENERIC: u8 = 0;
    }

    pub mod event {
        pub const TIMER: u8 = 0;
    }

    pub mod event_type {
        pub const START: u8 = 0;
        pub const STOP_ALL: u8 = 4;
    }

    pub mod activity_type {
        pub const MANUAL: u8 = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::value::event_type;

    #[test]
    fn event_type_stop_all_uses_the_fit_profile_value() {
        assert_eq!(event_type::START, 0);
        assert_eq!(event_type::STOP_ALL, 4);
    }
}
