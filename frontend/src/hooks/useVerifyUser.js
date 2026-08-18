import { useCallback, useState } from 'react';
import { useDispatch } from 'react-redux';
import { personLogin, personLogout } from '../Store/Slices/personSlice';
import axiosInstance, { clearTokens, getAccessToken } from '../axios';

/**
 * Restore the session on page load.
 *
 * The previous version read a `user` key that nothing wrote any more and called
 * /api/data/findperson, an endpoint this backend has never had — so every
 * refresh logged you out despite a valid token sitting in localStorage.
 */
export const useVerifyUser = () => {
    const [isVerifying, setIsVerifying] = useState(true);
    const dispatch = useDispatch();

    const verifystate = useCallback(async () => {
        if (!getAccessToken()) {
            dispatch(personLogout());
            setIsVerifying(false);
            return;
        }

        setIsVerifying(true);
        try {
            // The interceptor silently refreshes an expired access token, so
            // this succeeds for as long as the refresh token is valid.
            const { data } = await axiosInstance.get('Auth/profile');
            dispatch(personLogin(data));
        } catch (err) {
            clearTokens();
            dispatch(personLogout());
        } finally {
            setIsVerifying(false);
        }
    }, [dispatch]);

    return { verifystate, isVerifying };
};
