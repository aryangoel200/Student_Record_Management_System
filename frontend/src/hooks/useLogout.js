import { useDispatch } from 'react-redux';
import { personLogout } from '../Store/Slices/personSlice';
import axiosInstance, { clearTokens, getRefreshToken } from '../axios';

export const useLogout = () => {
    const dispatch = useDispatch();

    const logout = async () => {
        const refresh = getRefreshToken();
        // Blacklist the refresh token server-side so it cannot be replayed.
        // Best-effort: a failure here must not leave the user stuck logged in.
        if (refresh) {
            try {
                await axiosInstance.post('Auth/logout', { refresh });
            } catch (err) {
                // Already expired or revoked — nothing more to do.
            }
        }
        clearTokens();
        dispatch(personLogout());
    };

    return { logout };
};
